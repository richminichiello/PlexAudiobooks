package com.plexaudiobooks.data

import com.plexaudiobooks.api.PlexResourcesApi
import com.plexaudiobooks.api.PlexServerApi
import com.plexaudiobooks.api.PlexTvApi
import com.plexaudiobooks.data.local.*
import com.plexaudiobooks.data.model.*
import com.plexaudiobooks.util.SessionManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Result<Nothing>()
}

@Singleton
class PlexRepository @Inject constructor(
    private val plexTvApi: PlexTvApi,
    private val plexResourcesApi: PlexResourcesApi,
    private val plexServerApi: PlexServerApi,
    private val session: SessionManager,
    private val progressDao: PlaybackProgressDao,
    private val downloadDao: DownloadedBookDao,
    private val libraryDao: CachedLibraryDao
) {

    // ── URL helpers ─────────────────────────────────────────────────────────

    private fun url(path: String): String {
        val base = session.serverUrl?.trimEnd('/') ?: "http://localhost"
        return "$base/$path"
    }

    // Resolves the best reachable server URL from all stored candidates.
    // Call this at the start of any user-initiated action (play, download).
    // Returns the active base URL or null if nothing is reachable.
    suspend fun resolveAndRefreshServerUrl(): String? = withContext(Dispatchers.IO) {
        session.resolveActiveServerUrl()
    }

    // ── Auth ────────────────────────────────────────────────────────────────

    suspend fun createAuthPin(): Result<PlexPin> = withContext(Dispatchers.IO) {
        try {
            val resp = plexTvApi.createPin()
            if (resp.isSuccessful && resp.body() != null) {
                Result.Success(resp.body()!!)
            } else {
                Result.Error("Failed to create PIN: ${resp.code()}")
            }
        } catch (e: Exception) {
            Result.Error("Network error", e)
        }
    }

    suspend fun checkAuthPin(pinId: Long, code: String): Result<String?> =
        withContext(Dispatchers.IO) {
            try {
                val resp = plexTvApi.checkPin(pinId, code)
                if (resp.isSuccessful) {
                    Result.Success(resp.body()?.authToken)
                } else {
                    Result.Error("Pin check failed: ${resp.code()}")
                }
            } catch (e: Exception) {
                Result.Error("Network error", e)
            }
        }

    suspend fun fetchAndStoreUser(token: String): Result<PlexUser> =
        withContext(Dispatchers.IO) {
            try {
                val resp = plexTvApi.getUser(token)
                if (resp.isSuccessful && resp.body() != null) {
                    // plex.tv/api/v2/user returns user at root level (flat JSON)
                    val user = resp.body()!!
                    session.authToken = token
                    session.username = user.username.ifBlank { user.title ?: "" }
                    session.userThumb = user.avatarUrl
                    Result.Success(user)
                } else {
                    Result.Error("Failed to fetch user: ${resp.code()}")
                }
            } catch (e: Exception) {
                Result.Error("Network error: ${e.message}", e)
            }
        }

    suspend fun getHomeUsers(): Result<List<PlexHomeUser>> = withContext(Dispatchers.IO) {
        try {
            // Chronicle confirmed: must use the ACCOUNT-level token (from OAuth pin check),
            // not a switched user token (serverToken). authToken is always account-level.
            val token = session.authToken ?: return@withContext Result.Error("Not logged in")
            android.util.Log.d("PlexRepo", "getHomeUsers: calling with authToken (account-level)")
            val resp = plexTvApi.getHomeUsers(token)
            android.util.Log.d("PlexRepo", "getHomeUsers: HTTP ${resp.code()}")
            if (resp.isSuccessful) {
                val body = resp.body()
                val users = body?.users ?: emptyList()
                android.util.Log.d("PlexRepo", "getHomeUsers: body=${body != null}, size=${body?.size}, users=${users.size} — ${users.map { it.title }}")
                if (users.isEmpty() && body != null) {
                    // Body deserialized but users list is empty — log raw to diagnose key mismatch
                    android.util.Log.w("PlexRepo", "getHomeUsers: got body but 0 users — check JSON key casing")
                }
                Result.Success(users)
            } else {
                val errBody = resp.errorBody()?.string()
                android.util.Log.w("PlexRepo", "getHomeUsers failed: HTTP ${resp.code()} body=$errBody")
                Result.Success(emptyList())
            }
        } catch (e: Exception) {
            android.util.Log.e("PlexRepo", "getHomeUsers exception: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.Success(emptyList())
        }
    }

    suspend fun switchHomeUser(userUuid: String, pin: String?): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val token = session.authToken ?: return@withContext Result.Error("Not logged in")
                // Chronicle confirmed: use uuid (String), not numeric id
                val resp = plexTvApi.switchHomeUser(userUuid, token, pin)
                android.util.Log.d("PlexRepo", "switchHomeUser: HTTP ${resp.code()}")
                if (resp.isSuccessful && resp.body() != null) {
                    // Response is a flat PlexUser at the root — no "user" wrapper
                    val user = resp.body()!!
                    val newToken = user.authToken
                    android.util.Log.d("PlexRepo", "switchHomeUser: got token=${!newToken.isNullOrEmpty()}, username=${user.username}")
                    if (!newToken.isNullOrEmpty()) {
                        session.authToken = newToken
                        Result.Success(newToken)
                    } else {
                        Result.Error("No token in switch response")
                    }
                } else if (resp.code() == 401) {
                    Result.Error("Incorrect PIN — please try again")
                } else {
                    val errBody = resp.errorBody()?.string()
                    android.util.Log.w("PlexRepo", "switchHomeUser failed: ${resp.code()} $errBody")
                    Result.Error("Failed to switch user: HTTP ${resp.code()}")
                }
            } catch (e: Exception) {
                Result.Error("Network error: ${e.message}", e)
            }
        }


    // ── Server discovery via plex.tv/api/v2/resources ───────────────────────

    suspend fun discoverServers(): Result<List<PlexResource>> = withContext(Dispatchers.IO) {
        try {
            val token = session.authToken ?: return@withContext Result.Error("Not logged in")
            val resp = plexResourcesApi.getResources(token)
            if (resp.isSuccessful) {
                val servers = (resp.body() ?: emptyList()).filter { it.isServer }
                Result.Success(servers)
            } else {
                Result.Error("Could not reach Plex: ${resp.code()}")
            }
        } catch (e: Exception) {
            Result.Error("Network error: ${e.message}", e)
        }
    }

    // Returns all audio-capable library sections from the server.
    // Stores ALL valid connection URIs (local + remote + relay) so the app can
    // fall back gracefully when the user is away from home.
    suspend fun getServerLibraries(resource: PlexResource): Result<List<PlexDirectory>> =
        withContext(Dispatchers.IO) {
            val token = session.authToken ?: return@withContext Result.Error("Not logged in")
            val serverToken = resource.accessToken ?: token
            // Sort: local LAN first, direct remote second, relay last
            val connections = resource.connections.sortedBy { it.priority }

            // Collect ALL reachable URIs so we can try them all later
            val reachableUris = mutableListOf<String>()
            var firstSuccessfulSections: List<PlexDirectory>? = null

            for (connection in connections) {
                try {
                    val librariesUrl = "${connection.uri}/library/sections"
                    val resp = plexServerApi.getLibraries(librariesUrl, serverToken)
                    if (resp.isSuccessful) {
                        val sections = resp.body()?.mediaContainer?.directories ?: emptyList()
                        val audioSections = sections.filter {
                            it.type == "artist" || it.type == "album"
                        }
                        if (audioSections.isNotEmpty()) {
                            reachableUris.add(connection.uri)
                            if (firstSuccessfulSections == null) {
                                firstSuccessfulSections = audioSections
                                // Set active URL to the first (highest priority) working connection
                                session.serverUrl = connection.uri
                            }
                        }
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            if (firstSuccessfulSections != null) {
                // Store ALL reachable URIs in priority order for remote fallback
                session.allServerUrls = reachableUris
                session.serverToken = serverToken
                session.serverName = resource.name
                Result.Success(firstSuccessfulSections!!)
            } else {
                Result.Error("Could not connect to ${resource.name}")
            }
        }

    fun selectLibrary(section: PlexDirectory) {
        session.musicSectionId = section.key
    }

    suspend fun connectToServer(resource: PlexResource): Result<String> =
        withContext(Dispatchers.IO) {
            when (val result = getServerLibraries(resource)) {
                is Result.Success -> {
                    // Auto-select only if there is exactly one audio library
                    val libs = result.data
                    if (libs.size == 1) {
                        session.musicSectionId = libs.first().key
                        Result.Success(libs.first().key)
                    } else if (libs.isEmpty()) {
                        Result.Error("No music or audiobook libraries found on ${resource.name}")
                    } else {
                        // Multiple libraries — signal caller to present a picker
                        // Return special marker; ServerSetupViewModel handles navigation
                        Result.Success("__MULTIPLE_LIBRARIES__")
                    }
                }
                is Result.Error -> result
            }
        }

    // ── Manual server entry (fallback) ──────────────────────────────────────

    suspend fun discoverAudiobookLibrary(serverUrl: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val token = session.authToken ?: return@withContext Result.Error("Not logged in")
                val base = serverUrl.trimEnd('/')
                // Use the provided URL directly to build the libraries endpoint
                val librariesUrl = "$base/library/sections"
                val resp = plexServerApi.getLibraries(librariesUrl, token)
                if (resp.isSuccessful) {
                    val sections = resp.body()?.mediaContainer?.directories ?: emptyList()
                    val musicSection = sections.firstOrNull {
                        it.type == "artist" || it.type == "album" || it.type == "music"
                    }
                    if (musicSection != null) {
                        // Save server URL only after confirmed successful connection
                        session.serverUrl = base
                        session.musicSectionId = musicSection.key
                        session.serverName = "Plex Server"
                        Result.Success(musicSection.key)
                    } else {
                        Result.Error("No music/audiobook library found on this server")
                    }
                } else {
                    Result.Error("Server returned ${resp.code()} — check the URL and try again")
                }
            } catch (e: Exception) {
                Result.Error("Cannot reach server: ${e.message}", e)
            }
        }

    // ── Library ─────────────────────────────────────────────────────────────

    suspend fun fetchLibrary(forceRefresh: Boolean = false): Result<List<AudioBook>> =
        withContext(Dispatchers.IO) {
            val token = session.serverToken ?: session.authToken ?: return@withContext Result.Error("Not logged in")
            val sectionId = session.musicSectionId ?: return@withContext Result.Error("No library selected")

            // Return cache if not forcing a refresh and we have data
            if (!forceRefresh && libraryDao.getCount() > 0) {
                return@withContext Result.Success(emptyList()) // UI reads from Room Flow directly
            }

            try {
                val itemsUrl = url("library/sections/$sectionId/all")
                val resp = plexServerApi.getLibraryItems(itemsUrl, token)
                if (resp.isSuccessful) {
                    val items = resp.body()?.mediaContainer?.metadata ?: emptyList()
                    val books = items.map { it.toAudioBook() }
                    libraryDao.clearAll()
                    libraryDao.insertAll(items.map { it.toCachedEntity() })
                    Result.Success(books)
                } else {
                    Result.Error("Failed to load library: ${resp.code()}")
                }
            } catch (e: Exception) {
                Result.Error("Network error: ${e.message}", e)
            }
        }

    fun observeLibrary(): Flow<List<CachedLibraryEntity>> = libraryDao.getAllCached()

    suspend fun setCompleted(ratingKey: String, completed: Boolean) {
        libraryDao.setCompleted(ratingKey, completed)
    }

    fun searchLibrary(query: String): Flow<List<CachedLibraryEntity>> =
        libraryDao.searchLibrary("%$query%")

    suspend fun fetchBookDetail(ratingKey: String): Result<Pair<AudioBook, List<Chapter>>> =
        withContext(Dispatchers.IO) {
            val token = session.serverToken ?: session.authToken
                ?: return@withContext Result.Error("Not logged in")
            try {
                // Step 1: Fetch album-level metadata (title, author, thumb, duration)
                val metaUrl = url("library/metadata/$ratingKey")
                val metaResp = plexServerApi.getItemMetadata(metaUrl, token)
                val album = metaResp.body()?.mediaContainer?.metadata?.firstOrNull()
                    ?: return@withContext Result.Error("Book not found")

                // Step 2: Fetch children to get the track(s).
                // For a single M4B: returns exactly 1 track (the .m4b file).
                // For multi-file books: returns N tracks (one per audio file).
                // We accept ALL child types — "track", "episode", etc.
                val childrenUrl = url("library/metadata/$ratingKey/children")
                val childResp = plexServerApi.getChildren(childrenUrl, token)
                val tracks = (childResp.body()?.mediaContainer?.metadata ?: emptyList())
                    .filter { it.type != "album" && it.type != "artist" && it.type != "playlist" }

                // Step 3: For each track, fetch its embedded chapter data with includeChapters=1.
                // This is how Plex exposes M4B chapter markers — they live on the TRACK,
                // not the album. Without this call, chapters are always empty.
                val chapters: List<Chapter> = if (tracks.isNotEmpty()) {
                    val allChapters = mutableListOf<Chapter>()
                    var trackStartOffset = 0L  // cumulative offset for multi-file books

                    for (track in tracks) {
                        val trackUrl = url("library/metadata/${track.ratingKey}")
                        val chapterResp = plexServerApi.getTrackWithChapters(trackUrl, token)
                        val trackWithChapters = chapterResp.body()
                            ?.mediaContainer?.metadata?.firstOrNull()
                        val embeddedChapters = trackWithChapters?.chapters

                        if (!embeddedChapters.isNullOrEmpty()) {
                            // M4B with embedded chapter markers: use them directly.
                            // startTimeOffset/endTimeOffset are relative to the track start,
                            // so add trackStartOffset to make them relative to the book start.
                            embeddedChapters.forEach { pc ->
                                allChapters.add(Chapter(
                                    id    = pc.id,
                                    index = allChapters.size,
                                    title = pc.tag.ifBlank { "Chapter ${allChapters.size + 1}" },
                                    startMs = pc.startTimeOffset + trackStartOffset,
                                    endMs   = pc.endTimeOffset + trackStartOffset
                                ))
                            }
                        } else {
                            // No embedded chapters: treat the whole track as one chapter.
                            val dur = track.duration.takeIf { it > 0 } ?: 0L
                            allChapters.add(Chapter(
                                id    = track.ratingKey.toLongOrNull() ?: allChapters.size.toLong(),
                                index = allChapters.size,
                                title = track.title.ifBlank { "Chapter ${allChapters.size + 1}" },
                                startMs = trackStartOffset,
                                endMs   = trackStartOffset + dur
                            ))
                        }
                        trackStartOffset += track.duration.takeIf { it > 0 } ?: 0L
                    }
                    allChapters
                } else {
                    emptyList()
                }

                // Duration = sum of all track durations (album.duration is often wrong)
                val totalDuration = if (tracks.isNotEmpty())
                    tracks.sumOf { it.duration }
                else
                    album.duration

                // For the stream URL we use the first track's part key.
                // ExoPlayer streams the full file from the beginning, so for a single
                // M4B this is the whole book. For multi-file books we seek into the
                // correct file via the chapter list.
                val mediaKey = tracks.firstOrNull()
                    ?.media?.firstOrNull()?.parts?.firstOrNull()?.key
                    ?: album.media?.firstOrNull()?.parts?.firstOrNull()?.key

                // Collect ALL part keys (one per track file) for download.
                // For a single M4B book this is just one key.
                // For multi-file books this gives us every file to download.
                val allPartKeys = tracks.flatMap { track ->
                    track.media?.flatMap { media ->
                        media.parts?.map { it.key } ?: emptyList()
                    } ?: emptyList()
                }.filter { it.isNotBlank() }

                Log.d("PlexRepo", "Book '${album.title}': ${tracks.size} track(s), ${allPartKeys.size} part key(s): $allPartKeys")

                // Store track-level info for /:/timeline progress reporting.
                // Chronicle confirmed: timeline uses TRACK ratingKey + track duration,
                // not the album ratingKey. This is what makes Plex show "In Progress".
                val firstTrack = tracks.firstOrNull()
                val trackRatingKey = firstTrack?.ratingKey
                val trackDurationMs = firstTrack?.duration ?: 0L

                val book = album.toAudioBook().copy(
                    duration = totalDuration,
                    mediaKey = mediaKey,
                    allPartKeys = allPartKeys,
                    trackRatingKey = trackRatingKey,
                    trackDurationMs = trackDurationMs
                )

                Result.Success(Pair(book, chapters))
            } catch (e: Exception) {
                Result.Error("Network error: ${e.message}", e)
            }
        }

    // ── Progress ─────────────────────────────────────────────────────────────

    suspend fun saveProgress(ratingKey: String, title: String, author: String?,
                             positionMs: Long, durationMs: Long) {
        progressDao.saveProgress(
            PlaybackProgressEntity(ratingKey, title, author, positionMs, durationMs)
        )
    }

    suspend fun getProgress(ratingKey: String): Long? =
        progressDao.getProgress(ratingKey)?.positionMs

    fun observeLastPlayed(): Flow<PlaybackProgressEntity?> = progressDao.getLastPlayed()

    suspend fun reportProgressToPlex(ratingKey: String, key: String,
                                     positionMs: Long, durationMs: Long, state: String) {
        val token = session.serverToken ?: session.authToken ?: return
        try {
            // Use /:/timeline (Chronicle-confirmed correct endpoint).
            // key should be /library/metadata/{trackRatingKey}
            val trackKey = if (key.startsWith("/library")) key else "/library/metadata/$ratingKey"
            plexServerApi.reportTimeline(
                token = token,
                ratingKey = ratingKey,
                key = trackKey,
                timeMs = positionMs,
                state = state,
                duration = durationMs,
                hasMde = 1
            )
            android.util.Log.d("PlexRepo", "reportTimeline: ratingKey=$ratingKey state=$state pos=${positionMs/1000}s")
        } catch (e: Exception) {
            android.util.Log.w("PlexRepo", "reportTimeline failed: ${e.message}")
        }
    }

    // ── Downloads ───────────────────────────────────────────────────────────

    fun observeDownloads(): Flow<List<DownloadedBookEntity>> = downloadDao.getAllDownloads()

    suspend fun getDownload(ratingKey: String): DownloadedBookEntity? =
        downloadDao.getDownload(ratingKey)

    suspend fun saveDownload(entity: DownloadedBookEntity) =
        downloadDao.insertDownload(entity)

    suspend fun deleteDownload(ratingKey: String) =
        downloadDao.deleteDownloadByKey(ratingKey)

    // ── Mappers ──────────────────────────────────────────────────────────────

    private fun PlexItem.toAudioBook() = AudioBook(
        ratingKey = ratingKey,
        title = title,
        author = grandparentTitle ?: parentTitle,
        summary = summary,
        thumbPath = thumb,
        duration = duration,
        viewOffset = viewOffset,
        addedAt = addedAt,
        mediaKey = media?.firstOrNull()?.parts?.firstOrNull()?.key,
        metadataKey = key,  // e.g. /library/metadata/123 — used for progress reporting
        allPartKeys = media?.flatMap { m -> m.parts?.map { it.key } ?: emptyList() }
            ?.filter { it.isNotBlank() } ?: emptyList()
    )

    private fun PlexItem.toCachedEntity() = CachedLibraryEntity(
        ratingKey = ratingKey,
        title = title,
        author = grandparentTitle ?: parentTitle,
        summary = summary,
        thumbPath = thumb,
        durationMs = duration,
        viewOffset = viewOffset,
        addedAt = addedAt,
        mediaPartKey = media?.firstOrNull()?.parts?.firstOrNull()?.key
    )

    private fun PlexChapter.toChapter() = Chapter(
        id = id,
        index = index,
        title = tag,
        startMs = startTimeOffset,
        endMs = endTimeOffset
    )
}
