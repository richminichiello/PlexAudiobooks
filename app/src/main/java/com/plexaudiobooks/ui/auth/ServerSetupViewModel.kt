package com.plexaudiobooks.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.Result
import com.plexaudiobooks.data.model.PlexDirectory
import com.plexaudiobooks.data.model.PlexHomeUser
import com.plexaudiobooks.data.model.PlexResource
import com.plexaudiobooks.util.SessionManager
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SetupStep {
    object Loading : SetupStep()
    data class PickHomeUser(val users: List<PlexHomeUser>) : SetupStep()
    data class EnterPin(val user: PlexHomeUser) : SetupStep()
    data class PickServer(val servers: List<PlexResource>) : SetupStep()
    data class PickLibrary(val server: PlexResource, val libraries: List<PlexDirectory>) : SetupStep()
    data class Error(val message: String) : SetupStep()
    object Done : SetupStep()
}

@HiltViewModel
class ServerSetupViewModel @Inject constructor(
    private val repository: PlexRepository,
    private val session: SessionManager
) : ViewModel() {

    private val _step = MutableStateFlow<SetupStep>(SetupStep.Loading)
    val step: StateFlow<SetupStep> = _step

    private var pendingPinUser: PlexHomeUser? = null

    fun start() {
        viewModelScope.launch {
            _step.value = SetupStep.Loading

            // Step 1: Check for Plex Home users
            when (val result = repository.getHomeUsers()) {
                is Result.Success -> {
                    val users = result.data
                    Log.d("ServerSetupVM", "Home users received: ${users.size} — ${users.map { it.title }}")
                    if (users.size > 1) {
                        _step.value = SetupStep.PickHomeUser(users)
                    } else {
                        discoverServers()
                    }
                }
                is Result.Error -> {
                    // Home users endpoint failed — treat as single-user account
                    discoverServers()
                }
            }
        }
    }

    fun selectHomeUser(user: PlexHomeUser) {
        viewModelScope.launch {
            if (user.protected || user.restricted || user.hasPassword) {
                // PIN required — show PIN entry screen
                pendingPinUser = user
                _step.value = SetupStep.EnterPin(user)
            } else {
                // No PIN — switch directly
                switchUser(user, pin = null)
            }
        }
    }

    fun submitPin(pin: String) {
        val user = pendingPinUser ?: return
        viewModelScope.launch {
            switchUser(user, pin)
        }
    }

    private suspend fun switchUser(user: PlexHomeUser, pin: String?) {
        _step.value = SetupStep.Loading
        // Chronicle: use uuid (String) not numeric id for the switch endpoint
        when (val result = repository.switchHomeUser(user.uuid, pin)) {
            is Result.Success -> discoverServers()
            is Result.Error -> _step.value = SetupStep.Error(result.message)
        }
    }

    private suspend fun discoverServers() {
        _step.value = SetupStep.Loading
        when (val result = repository.discoverServers()) {
            is Result.Success -> {
                val servers = result.data
                when {
                    servers.isEmpty() -> _step.value = SetupStep.Error(
                        "No Plex servers found on your account."
                    )
                    servers.size == 1 -> selectServer(servers.first())
                    else -> _step.value = SetupStep.PickServer(servers)
                }
            }
            is Result.Error -> _step.value = SetupStep.Error(result.message)
        }
    }

    fun selectServer(server: PlexResource) {
        viewModelScope.launch {
            _step.value = SetupStep.Loading
            when (val result = repository.getServerLibraries(server)) {
                is Result.Success -> {
                    val libs = result.data
                    when {
                        libs.isEmpty() -> _step.value = SetupStep.Error(
                            "No music or audiobook libraries found on ${server.name}.\n" +
                            "Make sure your Plex library type is set to Music."
                        )
                        libs.size == 1 -> {
                            repository.selectLibrary(libs.first())
                            _step.value = SetupStep.Done
                        }
                        else -> _step.value = SetupStep.PickLibrary(server, libs)
                    }
                }
                is Result.Error -> _step.value = SetupStep.Error(result.message)
            }
        }
    }

    fun selectLibrary(server: PlexResource, library: PlexDirectory) {
        repository.selectLibrary(library)
        _step.value = SetupStep.Done
    }

    fun connectManually(url: String) {
        viewModelScope.launch {
            _step.value = SetupStep.Loading
            when (val result = repository.discoverAudiobookLibrary(url)) {
                is Result.Success -> _step.value = SetupStep.Done
                is Result.Error -> _step.value = SetupStep.Error(result.message)
            }
        }
    }
}
