package com.plexaudiobooks.data.model

import com.google.gson.annotations.SerializedName

// ── Plex API response wrappers ──────────────────────────────────────────────

data class PlexMediaContainer(
    @SerializedName("MediaContainer") val mediaContainer: MediaContainer
)

data class MediaContainer(
    @SerializedName("size") val size: Int = 0,
    @SerializedName("Metadata") val metadata: List<PlexItem>? = null,
    @SerializedName("Directory") val directories: List<PlexDirectory>? = null
)

data class PlexDirectory(
    @SerializedName("key") val key: String,
    @SerializedName("title") val title: String,
    @SerializedName("type") val type: String = ""
)

// ── Library item (audiobook or chapter) ────────────────────────────────────

data class PlexItem(
    @SerializedName("ratingKey") val ratingKey: String,
    @SerializedName("key") val key: String,
    @SerializedName("title") val title: String,
    @SerializedName("parentTitle") val parentTitle: String? = null,
    @SerializedName("grandparentTitle") val grandparentTitle: String? = null,
    @SerializedName("summary") val summary: String? = null,
    @SerializedName("type") val type: String,
    @SerializedName("thumb") val thumb: String? = null,
    @SerializedName("art") val art: String? = null,
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("viewOffset") val viewOffset: Long = 0,
    @SerializedName("lastViewedAt") val lastViewedAt: Long? = null,
    @SerializedName("addedAt") val addedAt: Long = 0,
    @SerializedName("index") val index: Int? = null,
    @SerializedName("parentIndex") val parentIndex: Int? = null,
    @SerializedName("Media") val media: List<PlexMedia>? = null,
    @SerializedName("Chapter") val chapters: List<PlexChapter>? = null
)

data class PlexMedia(
    @SerializedName("id") val id: Long,
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("bitrate") val bitrate: Int? = null,
    @SerializedName("audioChannels") val audioChannels: Int? = null,
    @SerializedName("audioCodec") val audioCodec: String? = null,
    @SerializedName("container") val container: String? = null,
    @SerializedName("Part") val parts: List<PlexMediaPart>? = null
)

data class PlexMediaPart(
    @SerializedName("id") val id: Long,
    @SerializedName("key") val key: String,
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("file") val file: String? = null,
    @SerializedName("size") val size: Long = 0,
    @SerializedName("container") val container: String? = null
)

data class PlexChapter(
    @SerializedName("id") val id: Long,
    @SerializedName("filter") val filter: String? = null,
    @SerializedName("index") val index: Int,
    @SerializedName("tag") val tag: String,
    @SerializedName("startTimeOffset") val startTimeOffset: Long,
    @SerializedName("endTimeOffset") val endTimeOffset: Long,
    @SerializedName("thumb") val thumb: String? = null
)

// ── Plex OAuth models ───────────────────────────────────────────────────────

// Plex v2 PIN creation returns snake_case fields; PIN check returns camelCase.
// We map both names and coalesce in the accessor property.
data class PlexPin(
    val id: Long,
    val code: String,
    @SerializedName("authToken")  val authTokenCamel: String? = null,
    @SerializedName("auth_token") val authTokenSnake: String? = null,
    @SerializedName("expiresAt")  val expiresAt: String? = null
) {
    val authToken: String? get() = authTokenCamel ?: authTokenSnake
}

// plex.tv/api/v2/user returns a flat JSON object at the root — no wrapper key.
// authToken is nullable because it may not be present on all response types.
data class PlexUser(
    val id: Long,
    val uuid: String = "",
    val username: String = "",
    val email: String = "",
    @SerializedName("thumb") val avatarUrl: String? = null,
    @SerializedName("authToken") val authToken: String? = null,
    @SerializedName("title") val title: String? = null   // display name
)

// Home users (managed profiles on a Plex Home account)
data class PlexHomeUser(
    val id: Long,
    val uuid: String = "",
    val title: String,
    val username: String? = null,
    @SerializedName("thumb") val avatarUrl: String? = null,
    // Plex v2 API returns these as Boolean, not Int
    @SerializedName("restricted")
    @com.google.gson.annotations.JsonAdapter(BooleanOrIntDeserializer::class)
    val restricted: Boolean = false,
    @SerializedName("protected")
    @com.google.gson.annotations.JsonAdapter(BooleanOrIntDeserializer::class)
    val protected: Boolean = false,
    val admin: Boolean = false,
    val hasPassword: Boolean = false
)

// plex.tv/api/v2/home/users returns flat JSON.
// Chronicle source confirmed: key is lowercase "users", NOT "User".
// {"size": 2, "users": [{...}, {...}]}
data class PlexHomeUsersResponse(
    @SerializedName("users") val users: List<PlexHomeUser>? = null,
    val size: Int = 0
)

// Switch home user response — returns a new token for the selected user
data class PlexSwitchUserResponse(
    @SerializedName("user") val user: PlexUser
)


// ── Server discovery models ──────────────────────────────────────────────────

data class PlexResource(
    @SerializedName("name") val name: String,
    @SerializedName("product") val product: String = "",
    @SerializedName("provides") val provides: String = "",
    @SerializedName("clientIdentifier") val clientIdentifier: String = "",
    @SerializedName("accessToken") val accessToken: String? = null,
    @SerializedName("connections") val connections: List<PlexConnection> = emptyList()
) {
    val isServer: Boolean get() = provides.contains("server")
}

// Plex returns "local" as Boolean (true/false) in v2 resources API
// but as Int (0/1) in some other endpoints. This adapter handles both.
class BooleanOrIntDeserializer : com.google.gson.JsonDeserializer<Boolean> {
    override fun deserialize(
        json: com.google.gson.JsonElement,
        typeOfT: java.lang.reflect.Type,
        context: com.google.gson.JsonDeserializationContext
    ): Boolean {
        return when {
            json.isJsonPrimitive && json.asJsonPrimitive.isBoolean -> json.asBoolean
            json.isJsonPrimitive && json.asJsonPrimitive.isNumber -> json.asInt != 0
            else -> false
        }
    }
}

data class PlexConnection(
    @SerializedName("protocol") val protocol: String = "https",
    @SerializedName("address") val address: String = "",
    @SerializedName("port") val port: Int = 32400,
    @SerializedName("uri") val uri: String = "",
    // Plex sends local as Boolean in v2 resources API — use custom deserializer
    @SerializedName("local") @com.google.gson.annotations.JsonAdapter(BooleanOrIntDeserializer::class)
    val local: Boolean = false,
    @SerializedName("relay") val relay: Boolean = false
) {
    val priority: Int get() = when {
        local && !relay -> 0    // Local LAN — fastest
        !local && !relay -> 1   // Direct remote
        else -> 2               // Relay — slowest fallback
    }
}
// ── Internal app domain models ──────────────────────────────────────────────

data class AudioBook(
    val ratingKey: String,
    val title: String,
    val author: String?,
    val summary: String?,
    val thumbPath: String?,   // relative path for thumbnail URL building
    val duration: Long,
    val viewOffset: Long,
    val addedAt: Long,
    val mediaKey: String?,    // first part key for streaming
    val allPartKeys: List<String> = emptyList(), // all part keys (one per track file)
    val metadataKey: String? = null,   // album metadata key (e.g. /library/metadata/albumId)
    val trackRatingKey: String? = null, // first track ratingKey — used for /:/timeline reporting
    val trackDurationMs: Long = 0L,     // first track duration — used for /:/timeline reporting
    val isDownloaded: Boolean = false,
    val downloadedPath: String? = null
) {
    val progressPercent: Float
        get() = if (duration > 0) viewOffset.toFloat() / duration else 0f
}

data class Chapter(
    val id: Long,
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long
)
