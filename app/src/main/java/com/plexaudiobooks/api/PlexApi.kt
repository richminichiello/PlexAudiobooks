package com.plexaudiobooks.api

import com.plexaudiobooks.data.model.*
import retrofit2.Response
import retrofit2.http.*

// ── Plex.tv OAuth API ───────────────────────────────────────────────────────
interface PlexTvApi {

    @FormUrlEncoded
    @POST("pins")
    suspend fun createPin(
        @Field("strong") strong: Boolean = true
    ): Response<PlexPin>

    @GET("pins/{id}")
    suspend fun checkPin(
        @Path("id") pinId: Long,
        @Query("code") code: String
    ): Response<PlexPin>

    @GET("user")
    suspend fun getUser(
        @Header("X-Plex-Token") token: String
    ): Response<PlexUser>

    @GET("home/users")
    suspend fun getHomeUsers(
        @Header("X-Plex-Token") token: String
    ): Response<PlexHomeUsersResponse>

    // Chronicle source confirms: path uses UUID (string), not numeric id.
    // Response is a flat PlexUser object at the root — no "user" wrapper key.
    @POST("home/users/{uuid}/switch")
    suspend fun switchHomeUser(
        @Path("uuid") uuid: String,
        @Header("X-Plex-Token") token: String,
        @Query("pin") pin: String? = null
    ): Response<PlexUser>
}

// ── Plex Media Server API ───────────────────────────────────────────────────
interface PlexServerApi {

    @GET
    suspend fun getLibraries(
        @Url url: String,
        @Header("X-Plex-Token") token: String
    ): Response<PlexMediaContainer>

    @GET
    suspend fun getLibraryItems(
        @Url url: String,
        @Header("X-Plex-Token") token: String,
        @Query("type") type: Int = 9,
        @Query("includeChapters") includeChapters: Int = 1
    ): Response<PlexMediaContainer>

    // Fetch children of an album (tracks within an audiobook).
    // Does NOT include embedded chapter data — call getTrackWithChapters per track for that.
    @GET
    suspend fun getChildren(
        @Url url: String,
        @Header("X-Plex-Token") token: String
    ): Response<PlexMediaContainer>

    // Fetch a single track with its embedded chapter markers.
    // For M4B files this returns the Chapter[] array embedded in the file.
    // Must pass includeChapters=1 — Plex omits chapters without this param.
    @GET
    suspend fun getTrackWithChapters(
        @Url url: String,
        @Header("X-Plex-Token") token: String,
        @Query("includeChapters") includeChapters: Int = 1
    ): Response<PlexMediaContainer>

    @GET
    suspend fun getItemMetadata(
        @Url url: String,
        @Header("X-Plex-Token") token: String
    ): Response<PlexMediaContainer>

    // Chronicle confirmed: /:/timeline is the correct endpoint for progress reporting.
    // /:/progress is deprecated and does not sync with the Plex dashboard.
    // ratingKey = track ratingKey (not album), time = ms from track start,
    // key = /library/metadata/{trackRatingKey}, hasMDE=1 enables media dashboard activity.
    @GET("/:/timeline")
    suspend fun reportTimeline(
        @Header("X-Plex-Token") token: String,
        @Query("ratingKey") ratingKey: String,
        @Query("key") key: String,
        @Query("time") timeMs: Long,
        @Query("state") state: String,
        @Query("duration") duration: Long,
        @Query("hasMDE") hasMde: Int = 1,
        @Query("identifier") identifier: String = "com.plexapp.plugins.library"
    ): Response<Unit>

    // Lightweight server identity check — used for connection probing.
    // Returns quickly even if the main library API is slow.
    @GET
    suspend fun checkServer(
        @Url url: String,
        @Header("X-Plex-Token") token: String
    ): Response<PlexMediaContainer>
}

// ── Plex.tv Resources API (server discovery) ────────────────────────────────
interface PlexResourcesApi {

    @GET("resources")
    suspend fun getResources(
        @Header("X-Plex-Token") token: String,
        @Query("includeHttps") includeHttps: Int = 1,
        @Query("includeRelay") includeRelay: Int = 1,
        @Query("includeIPv6") includeIPv6: Int = 1
    ): Response<List<PlexResource>>
}
