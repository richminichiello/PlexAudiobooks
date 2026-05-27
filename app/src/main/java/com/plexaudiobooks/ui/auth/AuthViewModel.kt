package com.plexaudiobooks.ui.auth

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.Result
import com.plexaudiobooks.data.model.PlexPin
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthState {
    object Idle : AuthState()
    object LoadingPin : AuthState()
    data class WaitingForAuth(val pin: PlexPin, val authUrl: String) : AuthState()
    object Polling : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: PlexRepository,
    private val session: SessionManager
) : ViewModel() {

    private val _authState = MutableLiveData<AuthState>(AuthState.Idle)
    val authState: LiveData<AuthState> = _authState

    private var pollJob: Job? = null
    private var currentPin: PlexPin? = null

    fun startAuth() {
        viewModelScope.launch {
            _authState.value = AuthState.LoadingPin
            when (val result = repository.createAuthPin()) {
                is Result.Success -> {
                    val pin = result.data
                    currentPin = pin
                    // Per Plex docs: native apps use POLLING flow, not Forwarding.
                    // forwardUrl is intentionally omitted — Plex blocks custom schemes
                    // (plexaudiobooks://) in forwardUrl. We just poll for the token.
                    val authUrl = buildAuthUrl(pin)
                    _authState.value = AuthState.WaitingForAuth(pin, authUrl)
                    startPolling(pin)
                }
                is Result.Error -> {
                    _authState.value = AuthState.Error(result.message)
                }
            }
        }
    }

    private fun startPolling(pin: PlexPin) {
        pollJob?.cancel()
        // Plex docs recommend polling once per second. We use 2s to be conservative.
        pollJob = viewModelScope.launch {
            var attempts = 0
            while (isActive && attempts < 300) { // 10 minutes at 2s intervals
                delay(2_000)
                attempts++
                if (attempts == 1) _authState.value = AuthState.Polling

                val result = repository.checkAuthPin(pin.id, pin.code)
                if (result is Result.Success) {
                    val token = result.data
                    if (!token.isNullOrEmpty()) {
                        // Store token immediately so the UI can proceed
                        // fetchAndStoreUser runs in background — we don't block on it
                        session.authToken = token
                        _authState.value = AuthState.Success
                        // Fetch full user profile in background (non-blocking)
                        viewModelScope.launch {
                            repository.fetchAndStoreUser(token)
                        }
                        return@launch
                    }
                }
            }
            if (isActive) {
                _authState.value = AuthState.Error("Sign-in timed out. Please try again.")
            }
        }
    }

    fun cancelAuth() {
        pollJob?.cancel()
        currentPin = null
        _authState.value = AuthState.Idle
    }

    private fun buildAuthUrl(pin: PlexPin): String {
        // Per official Plex documentation (forums.plex.tv/t/authenticating-with-plex/609370):
        // - URL uses hash fragment (#?) — params are read by JS on app.plex.tv
        // - forwardUrl is OMITTED for native app polling flow (custom schemes are blocked)
        // - context[device][product] shows app name on the Plex consent screen
        // - clientID must exactly match X-Plex-Client-Identifier sent in API headers
        val clientId = session.clientId
        return "https://app.plex.tv/auth#?" +
            "clientID=${Uri.encode(clientId)}" +
            "&code=${Uri.encode(pin.code)}" +
            "&context%5Bdevice%5D%5Bproduct%5D=${Uri.encode("PlexAudiobooks")}"
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
