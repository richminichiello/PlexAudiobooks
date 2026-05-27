package com.plexaudiobooks.util

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SessionManager"
        private const val PREF_FILE = "plex_secure_prefs"
        private const val KEY_AUTH_TOKEN       = "auth_token"
        private const val KEY_CLIENT_ID        = "client_id"
        private const val KEY_SERVER_URL       = "server_url"
        private const val KEY_SERVER_URLS      = "server_urls"
        private const val KEY_SERVER_NAME      = "server_name"
        private const val KEY_USERNAME         = "username"
        private const val KEY_USER_THUMB       = "user_thumb"
        private const val KEY_MUSIC_SECTION_ID = "music_section_id"
        private const val KEY_SERVER_TOKEN     = "server_token"
        private const val KEY_PLAYBACK_SPEED   = "playback_speed"
        private const val KEY_SKIP_FORWARD_SEC = "skip_forward_sec"
        private const val KEY_SKIP_BACK_SEC    = "skip_back_sec"
        private const val KEY_DOWNLOAD_HOURS   = "download_hours"
        private const val KEY_DOWNLOAD_LOCATION = "download_location"
        private const val KEY_LIBRARY_VIEW_MODE  = "library_view_mode"   // "grid" or "list"
        private const val KEY_LIBRARY_SORT       = "library_sort"        // "title","author","duration","added"
        private const val KEY_HIDE_COMPLETED     = "hide_completed"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = try {
        EncryptedSharedPreferences.create(
            context, PREF_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // EncryptedSharedPreferences can fail if the keystore is unavailable
        // (e.g. on first boot before the device is fully unlocked).
        // Fall back to clearing and recreating — user will need to log in again.
        android.util.Log.e("SessionManager", "EncryptedSharedPreferences init failed, clearing: ${e.message}")
        context.deleteSharedPreferences(PREF_FILE)
        EncryptedSharedPreferences.create(
            context, PREF_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Short-lived OkHttpClient used only for connection probing (5s timeout)
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // ── Auth ────────────────────────────────────────────────────────────────

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    val isLoggedIn: Boolean get() = authToken != null

    val clientId: String get() {
        var id = prefs.getString(KEY_CLIENT_ID, null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_CLIENT_ID, id).apply()
        }
        return id
    }

    // ── Server ───────────────────────────────────────────────────────────────

    // Last-known working URL — fast path, not guaranteed fresh
    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    // All candidate URIs stored at setup time (local LAN, direct remote, relay)
    var allServerUrls: List<String>
        get() = prefs.getString(KEY_SERVER_URLS, null)
            ?.split(",")?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) = prefs.edit()
            .putString(KEY_SERVER_URLS, value.joinToString(","))
            .apply()

    var serverName: String?
        get() = prefs.getString(KEY_SERVER_NAME, null)
        set(value) = prefs.edit().putString(KEY_SERVER_NAME, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var userThumb: String?
        get() = prefs.getString(KEY_USER_THUMB, null)
        set(value) = prefs.edit().putString(KEY_USER_THUMB, value).apply()

    var serverToken: String?
        get() = prefs.getString(KEY_SERVER_TOKEN, null) ?: authToken
        set(value) = prefs.edit().putString(KEY_SERVER_TOKEN, value).apply()

    var musicSectionId: String?
        get() = prefs.getString(KEY_MUSIC_SECTION_ID, null)
        set(value) = prefs.edit().putString(KEY_MUSIC_SECTION_ID, value).apply()

    // ── Playback ──────────────────────────────────────────────────────────────

    var playbackSpeed: Float
        get() = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PLAYBACK_SPEED, value).apply()

    var skipForwardSec: Int
        get() = prefs.getInt(KEY_SKIP_FORWARD_SEC, 30)
        set(value) = prefs.edit().putInt(KEY_SKIP_FORWARD_SEC, value).apply()

    var skipBackSec: Int
        get() = prefs.getInt(KEY_SKIP_BACK_SEC, 15)
        set(value) = prefs.edit().putInt(KEY_SKIP_BACK_SEC, value).apply()

    // ── Downloads ─────────────────────────────────────────────────────────────

    var downloadHours: Int
        get() = prefs.getInt(KEY_DOWNLOAD_HOURS, 4)
        set(value) = prefs.edit().putInt(KEY_DOWNLOAD_HOURS, value).apply()

    var downloadLocation: String
        get() = prefs.getString(KEY_DOWNLOAD_LOCATION, "internal") ?: "internal"
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_LOCATION, value).apply()

    fun getDownloadDir(): File {
        val dir = if (downloadLocation == "external") {
            context.getExternalFilesDir(null)?.let { File(it, "PlexAudiobooks") }
                ?: File(context.filesDir, "downloads")
        } else {
            File(context.filesDir, "downloads")
        }
        dir.mkdirs()
        return dir
    }

    // ── Connection resolution ─────────────────────────────────────────────────

    /**
     * Probes all stored server URLs in parallel and returns the first one that
     * responds successfully. Falls back to the cached serverUrl if all probes fail.
     *
     * Uses parallel probing (Chronicle's approach) rather than sequential — this
     * means we always get the fastest reachable URL whether on LAN or remote.
     * No hard timeout: each individual probe has a 5-second connect timeout.
     *
     * Why no reconnect timeout (unlike Chronicle): Chronicle's timeout caused their
     * "source unavailable" bug when the server took slightly longer than expected.
     * We simply return the best available option and let the caller handle null.
     */
    suspend fun resolveActiveServerUrl(): String? = withContext(Dispatchers.IO) {
        val token = serverToken ?: authToken ?: return@withContext serverUrl
        val candidates = allServerUrls.ifEmpty { listOfNotNull(serverUrl) }

        if (candidates.isEmpty()) return@withContext null

        // Probe all candidates in parallel — take the first success
        val probeJobs = candidates.map { url ->
            async {
                try {
                    val req = Request.Builder()
                        .url("$url/identity")
                        .head()
                        .addHeader("X-Plex-Token", token)
                        .build()
                    val resp = probeClient.newCall(req).execute()
                    // 200 or 401 both mean the server is reachable (401 = reachable but needs auth)
                    if (resp.isSuccessful || resp.code == 401) {
                        Log.d(TAG, "Connection probe success: $url")
                        url
                    } else {
                        Log.d(TAG, "Connection probe failed (${resp.code}): $url")
                        null
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Connection probe exception: $url — ${e.message}")
                    null
                }
            }
        }

        // Collect all results and pick the first successful one
        // Results come back in the order candidates were listed (priority order)
        val results = probeJobs.awaitAll()
        val best = results.firstOrNull { it != null }

        if (best != null && best != serverUrl) {
            Log.i(TAG, "Switched server URL from $serverUrl to $best")
            serverUrl = best
        }

        best ?: serverUrl  // fall back to last-known if all probes fail
    }

    // ── URL builders ──────────────────────────────────────────────────────────

    fun buildThumbUrl(thumbPath: String?): String? {
        val base = serverUrl ?: return null
        val path = thumbPath ?: return null
        val token = serverToken ?: authToken ?: return null
        return "$base$path?X-Plex-Token=$token"
    }

    fun buildStreamUrl(partKey: String): String? {
        val base = serverUrl ?: return null
        val token = serverToken ?: authToken ?: return null
        return "$base$partKey?X-Plex-Token=$token"
    }

    // ── Library preferences ──────────────────────────────────────────────────

    var libraryViewMode: String
        get() = prefs.getString(KEY_LIBRARY_VIEW_MODE, "grid") ?: "grid"
        set(value) = prefs.edit().putString(KEY_LIBRARY_VIEW_MODE, value).apply()

    var librarySort: String
        get() = prefs.getString(KEY_LIBRARY_SORT, "title") ?: "title"
        set(value) = prefs.edit().putString(KEY_LIBRARY_SORT, value).apply()

    var hideCompleted: Boolean
        get() = prefs.getBoolean(KEY_HIDE_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_COMPLETED, value).apply()

    // ── Logout ────────────────────────────────────────────────────────────────

    fun logout() {
        prefs.edit().apply {
            remove(KEY_AUTH_TOKEN)
            remove(KEY_SERVER_URL)
            remove(KEY_SERVER_URLS)
            remove(KEY_SERVER_NAME)
            remove(KEY_USERNAME)
            remove(KEY_USER_THUMB)
            remove(KEY_MUSIC_SECTION_ID)
            remove(KEY_SERVER_TOKEN)
            apply()
        }
    }
}
