# PlexAudiobooks — Developer Handoff

**Version:** 1.6.5 (versionCode 45)
**Language:** Kotlin
**Min SDK:** 26 | **Target SDK:** 35 | **Compile SDK:** 35

---

## Project Overview

Native Android audiobook player for Plex Media Server. Authenticates via Plex OAuth, browses music/audiobook libraries, plays M4B files with chapter navigation, tracks and syncs progress back to Plex, and supports offline playback via sliding-window read-ahead cache.

---

## Tech Stack

| Layer | Library |
|-------|---------|
| UI | MVVM, ViewBinding, Navigation Component (Safe Args) |
| DI | Hilt 2.52 |
| DB | Room 2.6.1 (version 2, one migration applied) |
| Network | Retrofit 2.11.0 + Gson + OkHttp 4.12.0 |
| Playback | ExoPlayer (Media3 1.5.0) |
| Media Session | `androidx.media:media:1.7.0` — MediaBrowserServiceCompat + MediaSessionCompat |
| Paging | Paging3 3.3.5 |
| Images | Glide 4.16.0 |
| Auth storage | EncryptedSharedPreferences |
| Background work | WorkManager 2.10.0 (Hilt-integrated) |
| Async | Coroutines 1.9.0 + Flow |

---

## Architecture

### Package Structure
```
com.plexaudiobooks/
  api/           PlexApi.kt          — Retrofit interfaces (PlexTvApi, PlexServerApi, PlexResourcesApi)
  data/
    local/       Entities, Daos, AudiobookDatabase
    model/       PlexModels.kt       — API response data classes
    PlexRepository.kt                — Single data source; injected into ViewModels
  di/            AppModule.kt        — Hilt providers
  service/       AudiobookPlaybackService.kt, BookDownloadWorker.kt
  ui/
    auth/        AuthFragment, ServerSetupFragment + ViewModels
    library/     LibraryFragment, LibraryViewModel, adapters
    detail/      DetailFragment, DetailViewModel
    playback/    PlaybackManager.kt, NowPlayingUiState.kt   — 1.6.0 singleton owner of playback state
    player/      PlayerSheetFragment.kt (BottomSheet overlay), ChapterAdapter.kt
    settings/    SettingsFragment
    downloads/   DownloadsFragment
    MainActivity.kt
  util/          SessionManager.kt   — EncryptedSharedPreferences wrapper
```

### Key Patterns
- **Singleton PlaybackManager (1.6.0)**: `ui/playback/PlaybackManager` owns ONE persistent `MediaControllerCompat` for the process lifetime — it is NOT torn down on fragment onStop the way the old PlayerFragment controller was. Every screen (mini-player, `PlayerSheetFragment`) collects `PlaybackManager.state: StateFlow<NowPlayingUiState>`. The fix for the old "playback dies, can't restart without force-quit" symptom. `play(ratingKey)` is the single entry point used by Continue Listening, Detail, and Downloads. The service is started via `startForegroundService` (not `startService`).
- **Player is a sheet, not a nav destination**: `playerFragment` was REMOVED from `nav_graph.xml`. Now Playing is a `PlayerSheetFragment` (`BottomSheetDialogFragment`) shown from `MainActivity.showPlayerSheet()`; swipe-down or back dismisses it. A persistent mini-player bar (the only way to reach the player) sits above the bottom nav.
- **Bottom navigation (3 tabs)**: `res/menu/bottom_nav.xml` has 3 tabs whose ids match nav destinations so `setupWithNavController` gives multi-back-stack: `libraryFragment`, `downloadsFragment`, `settingsFragment`. The old 4th "Now Playing" tab (`dashboardFragment` → `NowPlayingTabFragment`) was REMOVED post-1.6.0 — the mini-player bar is the player's sole entry point, so the tab was redundant. (Files deleted: `NowPlayingTabFragment.kt`, `fragment_now_playing_tab.xml`.)
- **Repository pattern**: all data access through `PlexRepository`. No direct DAO calls from ViewModels.
- **StateFlow**: ViewModels emit `UiState` sealed/data classes; Fragments collect. `PlaybackManager` emits `NowPlayingUiState`.
- **ConcatAdapter** (library screen): `ContinueListeningHeaderAdapter | BookAdapter (Paging3) | CompletedSectionAdapter` — critical that Paging3 adapter is never inside a NestedScrollView. `ContinueListeningHeaderAdapter` now also takes an `onBookLongClick` for the shelf/complete context menu.
- **WorkManager + Hilt**: `PlexAudiobooksApp` implements `Configuration.Provider` and injects `HiltWorkerFactory`. The default `WorkManagerInitializer` is removed in `AndroidManifest.xml` via `tools:node="merge"` on the `InitializationProvider` plus `tools:node="remove"` on the nested WorkManager `meta-data` child (functionally removes the default initializer).

---

## Room Database

**Version 4.** Migrations: 1→2 adds `completed` to `cached_library`; 2→3 adds `shelved` to `cached_library`; 3→4 adds `durable INTEGER NOT NULL DEFAULT 0` to `downloaded_books` (distinguishes an explicit full-book download from a read-ahead cache file — see Offline Caching).

### Tables
| Table | Entity | Purpose |
|-------|--------|---------|
| `playback_progress` | `PlaybackProgressEntity` | Local resume position; synced to Plex |
| `downloaded_books` | `DownloadedBookEntity` | Tracks local file path, size, `downloadedUpToMs`, and `durable` (true = explicit full-book download that persists across completion; false = read-ahead cache that auto-deletes on completion) |
| `cached_library` | `CachedLibraryEntity` | Library snapshot; includes `completed` flag |

### Key Queries (Daos.kt)
- `CachedLibraryPagingDao`: paged queries by title/author/duration/added — **all queries include `WHERE completed = 0 OR completed IS NULL`** so completed books never appear in the main grid
- `getPagedExcludeCompleted()`: used when hide-completed toggle is on (redundant with above but kept for explicit intent)
- `getContinueListening()`: JOIN with `playback_progress` using `CAST(ratingKey AS TEXT)` on both sides to prevent silent type mismatches; excludes completed books; no upper-bound position filter (avoids falsely excluding books near the end)
- `getCompleted()`: returns `Flow<List<CachedLibraryEntity>>` where `completed = 1`
- `getCompletedKeys()`: returns `List<String>` of completed ratingKeys — used by `fetchLibrary` to preserve completed state across cache refreshes
- `getDownloadedKeys()`: returns `Flow<List<String>>` of downloaded ratingKeys for offline badge
- `setCompleted(ratingKey, completed)`: updates the `completed` column

---

## Authentication Flow

1. `AuthFragment` creates a Plex OAuth PIN via `POST plex.tv/api/v2/pins`
2. Opens `app.plex.tv/auth#?clientID=...&code=...` in a Custom Tab
3. Polls `GET plex.tv/api/v2/pins/{id}?code=...` every 2s until token appears
4. On token receipt: store immediately, emit `AuthState.Success`, fetch user profile in background
5. `ServerSetupFragment` then calls `getHomeUsers()` (flat JSON `{"users":[...]}`) and shows picker if >1
6. PIN-protected users use `POST plex.tv/api/v2/home/users/{uuid}/switch?pin=...` (uuid, not id)
7. Server discovery via `GET clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1`
8. All valid connection URIs stored in `SessionManager.allServerUrls` for remote fallback

### Critical Model Notes
- `plex.tv/api/v2/user` returns **flat** JSON — `Response<PlexUser>` not `Response<PlexUserResponse>`
- `plex.tv/api/v2/home/users` returns `{"users":[...]}` — lowercase key, flat (no MediaContainer)
- `plex.tv/api/v2/home/users/{uuid}/switch` returns **flat** `PlexUser` — `Response<PlexUser>`
- `PlexHomeUser.restricted` and `.protected` are **Boolean** in v2 API — use `BooleanOrIntDeserializer`
- `PlexConnection.local` is **Boolean** in v2 API — use `BooleanOrIntDeserializer`

---

## Connection Resolution

`SessionManager.resolveActiveServerUrl()` probes all stored URLs in **parallel** (async coroutines, 5s connect + 5s read timeout each). It does **not** short-circuit on the first success — it `awaitAll`s every probe (up to ~5s for the slowest), then picks the first non-null result in candidate-priority order (LAN preferred, then remote, then relay). So worst-case connection time is bounded by one 5s probe window, not the sum of them. Called at the start of playback and download. Prevents being stuck on a LAN IP when away from home.

---

## Playback Architecture

**Service:** `AudiobookPlaybackService extends MediaBrowserServiceCompat`
**NOT** Media3 `MediaSessionService` — that API does not allow overriding `PlaybackState.position`, making chapter-relative progress impossible in notifications and car displays.

### Seek Contract (critical — has caused many bugs)
All seek paths use **chapter-relative positions** into `onSeekTo`:

| Source | What it sends | Service does |
|--------|---------------|--------------|
| Notification bar drag | chapter-relative (0..chapterDuration) | `+ chapter.startMs` → absolute |
| In-app seekbar / skip buttons | `PlaybackManager.seekAbsolute(abs)` subtracts `chapter.startMs` → chapter-rel | `+ chapter.startMs` → absolute |
| Chapter list tap | `PlaybackManager.seekAbsolute(chapter.startMs)` (PlayerSheetFragment) | service `onSeekTo` receives chapter-rel → `+ chapter.startMs` → absolute |

`PlaybackStateCompat.position` = chapter-relative (what notifications use)
`PlaybackState.extras[EXTRA_ABSOLUTE_POSITION]` = raw ExoPlayer position (`PlaybackManager` reads it for all calculations; the sheet derives its chapter-relative seekbar from it)

### Chapter Loading
- Chapters sent via `AudiobookPlaybackService.pendingChapters` (companion object, in-process)
- `startStateUpdating()` loop polls `pendingChapters` every 500ms — picks up chapters whenever they arrive
- On chapter change: `updateSessionMetadata()` sets `METADATA_KEY_DURATION` = chapter duration, `METADATA_KEY_TITLE` = chapter title

### Progress Reporting
- Local save: `repository.saveProgress()` → `playback_progress` table every 10s
- Plex sync: `GET /:/timeline` with `hasMDE=1` — **not** `/:/progress` (deprecated)
- **Timeline keys off the TRACK ratingKey, not the album ratingKey and not a part key.** `PlaybackManager.play()` passes `AudioBook.trackRatingKey` (populated only by `fetchBookDetail` — neither the `cached_library` row nor the `downloaded_books` row carries it) into `EXTRA_KEY` → the service's `currentKey` → `reportProgressToPlex()`, falling back to the album ratingKey when there are no track children. `reportProgressToPlex()` always builds `key=/library/metadata/{ratingKey}` itself (stripping any existing `/library/metadata/` prefix) — do NOT reintroduce the old `if (key.startsWith("/library"))` heuristic that passed a `/library/parts/…` part key through unchanged, which silently dropped every progress report (the 1.6.5 regression — see Resolved in 1.6.5). The local Room save stays keyed by the ALBUM ratingKey (`currentRatingKey`) so Continue Listening's JOIN still matches.
- Always uses absolute ExoPlayer position, never chapter-relative
- `saveProgress()` falls back to `currentChapters.last().endMs` for duration when `exoPlayer.duration` returns `-1` (common before full buffer); skips save if position is 0
- **Auto-complete-on-save: NOT CURRENTLY WIRED.** The doc previously claimed `saveProgress()` silently marked the book complete when within `autoCompleteMinutes` of the end. That threshold check does not exist in `AudiobookPlaybackService.saveProgress()` (`:680-696`) — `autoCompleteMinutes` (`SessionManager.kt:254`) is defined but read nowhere. Today a book only gets the `completed` flag via natural `STATE_ENDED` or manual "Mark as Read". Tracked as a pre-1.6.0 gap — deferred until the re-arch is stable.
- **Natural completion**: when `Player.STATE_ENDED` fires and it is a genuine book end (not a truncated local file), the book is marked complete, a non-durable cache file is auto-evicted (durable downloads spared), and the service stops via the shared `stopPlaybackAndService()` helper (see below). Do NOT tear playback down inline inside the ExoPlayer listener — the old real-end branch did that with no `exoPlayer.stop()` and no `stopSelf()`, leaving the service without a foreground notification so Android 12+ killed it mid-callback → the app disappeared at end of book (and was likely the replay-of-completed-book crash too).

### End-of-Book Workflow (critical)
When `Player.STATE_ENDED` fires, the service checks whether this is a real book end or a truncated local file end. The branch is gated on **`currentSourceIsLocal`** (a service field set in `playBook()` from `streamUrl.startsWith("file://")`, reset on book switch) — robust because `exoPlayer.currentMediaItem` can be null/stale across the `setMediaItem` transition mid-callback:
- If `currentSourceIsLocal` AND `download.downloadedUpToMs < download.durationMs` AND `pos < durationMs - 10_000`: **local file is truncated** — switch to streaming from server and continue playback from current position; immediately set `currentSourceIsLocal = false` so the *next* `STATE_ENDED` (from the just-switched server stream) routes to real-end instead of looping back into this branch
- Otherwise (a server stream that ended, or a fully-cached local file that played to the end): genuine end — save progress, mark complete, **auto-evict a non-durable cache** (`if (download?.durable != true) repository.deleteDownloadAndFile(ratingKey)` — covers `download == null` and non-durable rows; a durable full-book download is spared and stays on disk until the user explicitly removes it), reset position to 0, then stop the service via the shared `stopPlaybackAndService()` helper.

**`stopPlaybackAndService()` (added in 1.6.4 to fix the completion-crash found in 1.6.3 testing):** the single service teardown path — cancels `stateUpdateJob`/`progressJob`, `abandonAudioFocus()`, `exoPlayer.stop()`, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`. Called from BOTH the real-end branch above AND `MediaSession.Callback.onStop()` so teardown happens exactly once and the service actually stops. The old real-end branch did a subset of this *inline inside the `onPlaybackStateChanged` ExoPlayer listener* — with `exoPlayer.stop()` and `stopSelf()` missing. That left the service stripped of its foreground notification (but not stopped) while a suspend `deleteDownloadAndFile` interleaved mid-callback, so on Android 12+ the system killed the un-foregrounded service within seconds → the app "just disappeared" at end of book (no crash dialog), streaming books worst because the file delete ran there. Routing through `stopPlaybackAndService()` stops ExoPlayer on the main thread (the real-end branch runs on `serviceScope = Dispatchers.Main`) and actually stops the service. **Do not reintroduce inline teardown in the real-end branch; do not call `stopSelf()`/`stopForeground()` from there directly — go through the helper.** This is almost certainly the same defect as Open Issue #1 (replay-of-completed-book `ForegroundServiceStartNotAllowedException`) — both reached the same half-stopped state; verify by replaying a completed book and watching logcat for the exception.

**Why the `currentSourceIsLocal` gate (fixed 1.6.3):** before this, the truncated-file branch was gated only on the existence of a partial read-ahead `download` row (which a *streamed* book also has, because `triggerReadAheadIfNeeded` runs on every play including streams). A genuine server-stream `STATE_ENDED` reported `pos` slightly short of `duration` (ExoPlayer's last buffered position), so the OLD guard `pos < durationMs - 10_000` was true → the code misclassified the real end as a truncated local file, switched to the *same* server stream, and `return@launch` — never marking complete, never evicting, looping forever near the end. That's why streaming a book to the end left the read-ahead cache on disk.

**Manual "Mark as Read"** (`LibraryViewModel.markCompleted`) is the other completion path: it calls `repository.setCompleted(ratingKey, true)`, then evicts a non-durable cache the same way. Marking a book **unread** (`completed = false`) deletes nothing — undo must not free a downloaded book.

`repository.deleteDownloadAndFile(ratingKey)` is the centralized correct delete: removes the on-disk file (if present) AND the `downloaded_books` row. The old `repository.deleteDownload(ratingKey)` removed only the DB row and orphaned the file — all explicit delete paths (Detail "Remove download", Downloads trash button) now use `deleteDownloadAndFile`.

When replaying a completed book, `PlaybackManager.play()` (`PlaybackManager.kt:151`) checks `cached.completed` and resets the start position to `0L` when `rawPosition >= bookDuration - 30_000` (i.e. the saved position is near the end), then calls `repository.setCompleted(ratingKey, false)` so the book returns to Continue Listening. This prevents a crash caused by ExoPlayer trying to seek to a near-end position on a fresh stream load. (This logic moved from the deleted `PlayerViewModel.loadBook()` into `PlaybackManager` during the 1.6.0 re-arch.)

### ForegroundServiceStartNotAllowedException (Android 12+)
A background start on Android 12+ throws `ForegroundServiceStartNotAllowedException`. **All three call sites** that can reach a foreground start — `onPlay` (`:223`), `playBook` (`:327`), and `onIsPlayingChanged` (`:474`) — now route through `safeStartForeground()` (`:608-614`), which wraps `startForegroundNotification()` in a try/catch so a background-restricted start logs a warning instead of crashing the service (the "playback dies, can't restart without force-quit" symptom). The notification reappears next time the app is foregrounded. **Do not remove `safeStartForeground()`.**

---

## Offline Caching / Read-Ahead Architecture

### Design: Sliding Cache Window
The app maintains a local audio file for each book that covers a window of audio ahead of the current playback position. The window size is controlled by `SessionManager.downloadHours` (default `4`, hardcoded in `SessionManager.kt:147`; the Settings picker offers `1, 2, 3, 4, 6, 8` hours — note 5 and 7 are absent from the picker).

### How It Works
1. **On every play** (first and subsequent): `triggerReadAheadIfNeeded()` (`AudiobookPlaybackService.kt:700-729`), invoked from `onStartCommand()`, computes `targetCachedUpToMs = (exoPlayer.currentPosition + downloadHours * 3600 * 1000L).coerceAtMost(durationMs)`. If a download already exists and `downloadedUpToMs >= targetCachedUpToMs` (the window still covers the playhead), it does nothing. Otherwise it enqueues `BookDownloadWorker.buildRequest(...)` with that target — so the "first play" (no existing download) and "subsequent play" (existing download, user has listened forward) are the same unified path, differing only in whether the skip-guard fires.
2. **Worker extend path** (single-file books only — `partKeys.size == 1 && existingDownload != null && file exists`): `BookDownloadWorker` sends a `Range: bytes=N-` HTTP request to Plex to fetch only the missing bytes, appends them to the existing file, updates `downloadedUpToMs` in the DB.
3. **Worker fresh path** (multi-part books, or no existing file): computes `maxBytes` from `desiredMs`, where `desiredMs` is `targetCachedUpToMs` **only if `> 0`**; if `targetCachedUpToMs == 0` it falls back to `downloadHours * 3600 * 1000L` from byte 0. (This is why the Detail-button passing `0` behaves like a read-ahead rather than a full download.)
4. **Offline playback**: `PlaybackManager.buildStreamUrl()` (`PlaybackManager.kt:275`) returns a `file://` path only when the resume position is within the cached window: `safeLocal = (cachedUpTo <= 0 && positionMs == 0L) || (cachedUpTo > 0 && positionMs <= cachedUpTo)`. When `downloadedUpToMs` is unknown/`0`, the local file is used only from the very start; otherwise it streams so ExoPlayer doesn't seek past the end of a truncated local file. Falls back to the server stream URL otherwise. (This logic moved from the deleted `PlayerViewModel.buildStreamUrl()` into `PlaybackManager` during the 1.6.0 re-arch; `PlayerViewModel` no longer exists.)
5. **WorkManager network constraint**: `setRequiredNetworkType(NetworkType.CONNECTED)` is set on every `buildRequest()` (including extend jobs, which reuse `buildRequest`) — jobs queue silently when offline and fire automatically on reconnect.

### Range Request Fallback
If the server returns `200` instead of `206 Partial Content` for the Range request, the extend is skipped safely — no data corruption, existing cache preserved. A warning is logged: `"Server returned N instead of 206 — Range not supported. Keeping existing cache."`

### Detail Screen Download Button (durable full-book download, fixed 1.6.2)
The Download button in `DetailFragment` → `DetailViewModel.startDownload()` calls `BookDownloadWorker.buildRequest()` with `targetCachedUpToMs = book.duration` AND `durable = true`. Passing the full duration makes the worker treat anything `>= contentLength` as "download full file" (bypassing the `downloadHours` cap entirely), and `durable = true` records the row so it is **spared from completion auto-eviction**. The button's "Remove download" toggle (via `observeDownloadWork()`) calls `DetailViewModel.deleteDownload()` → `repository.deleteDownloadAndFile()`, which now deletes both the file AND the row (the old row-only delete orphaned the file).

### BookDownloadWorker Key Constants
| Constant | Purpose |
|----------|---------|
| `KEY_RATING_KEY` | Book ratingKey |
| `KEY_PART_KEYS` | Comma-separated part keys |
| `KEY_DURATION_MS` | Full book duration in ms |
| `KEY_TARGET_CACHED_UP_TO_MS` | Target end of cache window in ms (0 = use downloadHours from 0; Detail Download passes the full `durationMs` so the whole book is fetched) |
| `KEY_DURABLE` | true = explicit full-book download that persists across completion; false (default) = read-ahead cache auto-deleted on completion |
| `KEY_PROGRESS` | WorkManager progress output (0–100) |
| `KEY_ERROR` | WorkManager failure output message |
| `TAG_DOWNLOAD` | Fixed tag on all download workers for bulk query |

---

## Now Playing Load Sequence (1.6.0)

The player is no longer a fragment with its own load lifecycle. `PlaybackManager.play()` (the single entry point called by Continue Listening, Detail, and Downloads) does a staged load and emits `NowPlayingUiState`; the mini-player and `PlayerSheetFragment` collect `PlaybackManager.state: StateFlow<NowPlayingUiState>`.

1. **Instant (Room only)**: `play()` reads `repository.getCachedBook()`, `getDownload()`, and `getProgress()` — all local, no network. Builds an `AudioBook` for the UI and emits `_state` with `book` + `positionMs` populated and `chapters = emptyList()`. Cover art (already in Glide's disk cache from the library screen) renders immediately.
2. **Network (if reachable)**: `play()` calls `repository.fetchBookDetail()` to fetch chapters (skipped on failure for a downloaded book — offline is fine). Pushes them to `AudiobookPlaybackService.pendingChapters` (in-process companion), which the service's `startStateUpdating()` loop picks up whenever they arrive. Emits the full state with `chapters`.
3. **Position polling**: `PlaybackManager.pollPosition()` ticks every 500ms reading `EXTRA_ABSOLUTE_POSITION` from the controller's `PlaybackState` extras, re-deriving the chapter index/duration. The chapter-relative seekbar in `PlayerSheetFragment` (driven by `currentChapter()` lookup against `positionMs`) is only meaningful once `chapters.isNotEmpty()` — so the sheet's `render()` falls back to a book-level seekbar (book-duration-relative, 0–1000) when there's no chapter data, preventing the book-level seekbar flash.

### Error Handling on No Connectivity
If `repository.resolveAndRefreshServerUrl()` throws, `play()` writes `state.error = "Cannot reach server: ..."` and aborts (no stream URL built). If `fetchBookDetail()` fails for a downloaded book, chapters stay empty and playback proceeds from the local file — the previously-rendered cover/position (if any) are preserved because `play()` does `_state.value.copy(...)` rather than a full state replace.

---

## SessionManager (util/SessionManager.kt)

Single `@Singleton` wrapping `EncryptedSharedPreferences`. Key fields:

| Field | Purpose |
|-------|---------|
| `authToken` | Account-level Plex token (always account, never switched-user) |
| `serverToken` | Managed-user token after home user switch |
| `serverUrl` | Last-known working server URL |
| `allServerUrls` | All known URIs (LAN + remote + relay), comma-separated |
| `clientId` | Stable UUID identifying this app install |
| `musicSectionId` | Selected library section key |
| `librarySort` | "title" / "author" / "duration" / "added" |
| `libraryViewMode` | "grid" / "list" |
| `hideCompleted` | Boolean |
| `downloadHours` | default 4 — controls read-ahead cache window size (Settings picker offers 1/2/3/4/6/8) |
| `downloadLocation` | "internal" / "external" |
| `autoCompleteMinutes` | Int, default 5 — **NOT CURRENTLY WIRED**: intended minutes-remaining-to-auto-complete threshold, but `saveProgress()` does not read it (see Known Issue #4). Stored for future use. |

---

## Library Screen Architecture

The library screen uses a `ConcatAdapter` with three child adapters in a single `RecyclerView`:

```
ConcatAdapter
  ├── ContinueListeningHeaderAdapter  (always 1 item; hidden at 0×0 when list is empty)
  ├── BookAdapter (Paging3)           (main library grid/list; always excludes completed books)
  └── CompletedSectionAdapter         (0 items when no completed books; 1 item otherwise)
```

### ContinueListeningHeaderAdapter
- Always reports `getItemCount() = 1` so its ViewHolder is created immediately on screen load
- When items list is empty: `onBindViewHolder` sets `visibility = GONE` and `layoutParams(0, 0)` so it takes no space
- `isGridMode = true`: shows horizontal carousel (`rvContinueListening`)
- `isGridMode = false`: shows vertical list (`listContainer`) using `item_book_list.xml`

### CompletedSectionAdapter
- Reports `getItemCount() = 0` when empty
- `isGridMode = true`: renders books in a 2-column grid via nested `RecyclerView` + `CompletedGridAdapter`
- `isGridMode = false`: inflates `item_book_list.xml` rows directly into a `LinearLayout` container
- Long-press on any item triggers the "Mark as Unread" dialog

### SpanSizeLookup (GridLayoutManager)
```kotlin
override fun getSpanSize(position: Int): Int {
    if (!::completedSection.isInitialized || !::concatAdapter.isInitialized) return 1
    if (position == 0) return lm.spanCount          // always full width (Continue Listening)
    val sc = completedSection.itemCount
    val total = concatAdapter.itemCount
    return if (sc > 0 && position >= total - sc) lm.spanCount else 1
}
```

### Completed + Shelved State Persistence
`fetchLibrary()` in `PlexRepository` preserves user-set **completed AND shelved** flags across cache refreshes, and does the whole refresh as one atomic transaction so reactive Flows (notably `getContinueListening`, which JOINs `cached_library`) emit exactly once:
1. Snapshot `libraryDao.getCompletedKeys()` and `libraryDao.getShelvedKeys()` before wiping the cache.
2. `libraryDao.refreshCachedLibrary(fresh, completedKeys, shelvedKeys)` — a single `@Transaction` method (`Daos.kt:81-91`) doing `clearAll() → insertAll(fresh) → setCompleted(key,true) for each → setShelved(key,true) for each`.
3. The previous separate `clearAll/insertAll/setCompleted` sequence emitted a transient empty list mid-refresh, which made the Continue Listening section flicker and disappear on swipe-refresh — the transactional refresh fixes that.

---

## Implemented Features

- **Plex OAuth** with home user picker, PIN prompt, server/library selection
- **Library browsing**: grid/list toggle, sort by title/author/duration/date, search
- **Continue Listening section**: shows books with progress, not completed and not shelved; horizontal carousel in grid mode, vertical list in list mode; long-press offers "Put this book back on the shelf" (hide from Continue Listening, resume preserved, stays in browse) and "Mark as Read"
- **Shelved state (1.6.0)**: "Put back on the shelf" hides a book from Continue Listening without marking it completed; resume position preserved; stays visible in the main browse grid; returns to Continue Listening automatically the next time it's played (`PlaybackManager.play()` calls `setShelved(ratingKey,false)`)
- **Completed section**: appears at bottom when any books are marked complete; respects grid/list toggle; persists across app restarts and cache refreshes
- **Natural completion only** (auto-complete-on-save NOT wired): a book is marked complete when `Player.STATE_ENDED` fires a genuine book end, or via manual "Mark as Read". The previously-claimed near-end `autoCompleteMinutes` auto-mark does NOT exist in `saveProgress()` — see Known Issue #4.
- **Replay completed book**: resets position to 0 so replaying a completed book starts from the beginning without crashing
- **Offline badge**: amber "OFFLINE" tag on book cards when downloaded; updates reactively via `collect`
- **Book detail**: chapter list, download button with circular progress indicator, play button
- **Playback**: M4B chapter extraction (`includeChapters=1` per track), chapter navigation, variable speed (0.5–2.0×), skip forward/back (configurable), chapter-relative seekbar
- **Notification + car display**: chapter-relative progress bar and chapter title via `PlaybackStateCompat`
- **Audio focus**: pauses on phone call/nav, resumes on focus regain; `ForegroundServiceStartNotAllowedException` caught safely on Android 12+
- **Plex progress sync**: `/:/timeline` with `hasMDE=1`, resumes from server after reinstall
- **Sliding read-ahead cache**: configurable hours (default 4; Settings picker offers 1/2/3/4/6/8), extends automatically as user progresses, uses HTTP Range requests to append only missing bytes, WorkManager queues extend jobs offline and fires on reconnect
- **Local-first playback**: streams from local file when resume position is within the cached window (`safeLocal = cachedUpTo<=0 && positionMs==0L || cachedUpTo>0 && positionMs<=cachedUpTo`), falls back to server stream when beyond cache
- **Truncated file detection**: when ExoPlayer hits end of a partial local file, service switches to streaming seamlessly rather than treating it as end-of-book
- **Now Playing fast load**: cover art renders immediately from Glide disk cache; chapter-relative seekbar waits for chapter data before showing
- **Remote connection**: parallel server URL probing with fallback to relay
- **Settings**: skip times, download hours, download location, storage display, clear cache, sort/view preferences, sign out

---

## Known Issues / Pending

### Resolved
- **Streaming books played no audio (1.6.0 regression, fixed 2026-08-04)**: tapping a non-downloaded book → Detail → Play populated the mini-player (state had a `book`) but produced no audio — `play()` bailed before `sendPlayIntent`. Root cause: `play()` built `displayBook` from the `cached_library` row, whose `mediaPartKey` is `null` (the library `/all` endpoint returns album-level items with no parts), so `buildStreamUrl()` hit `partKey = book.mediaKey ?: return null` and returned null → `play()` returned at the "Could not build a stream URL" branch before sending the start intent. Downloaded books worked because their `file://` branch never reads `mediaKey`. Fix: `play()` now keeps the full `AudioBook` from the `fetchBookDetail()` it already calls (`Pair<AudioBook, List<Chapter>>`) and upgrades `displayBook` with the real part key (`mediaKey`/`allPartKeys`) when the cached row lacks them, before `sendPlayIntent`/`buildStreamUrl`/`_state.value.book`. No new network call; downloaded path untouched. See `PlaybackManager.kt:219-247`. (Logs tagged `AudiobookService` were filtered by Samsung OneUI in both captures; root cause was pinned by code trace.)
- **Fresh install started books from the beginning instead of resuming (fixed 2026-08-04)**: on a brand-new install (empty Room `playback_progress`), tapping Play on a book with server-saved progress started at position 0. Root cause: `PlaybackManager.play()` derived `rawPosition` only from `repository.getProgress(ratingKey)` (Room) — on a fresh install that returns `null` → `0L`. The server-saved offset was already available in `CachedLibraryEntity.viewOffset` (parsed from `/all`'s `viewOffset` during `fetchLibrary`) but `play()` ignored it. Fix: `play()` now reads the cached row's `viewOffset` as a fallback when there is no local Room row; when this server-fallback is the position source (`isServerResume`), it emits `showResumePrompt` on `NowPlayingUiState` and **suspends** the play coroutine on a `CompletableDeferred<Boolean?>` while `MainActivity` shows a "Resume from {Hh Mm}" / "Start from beginning" dialog. `confirmResume(true)` resumes from the server position; `confirmResume(false)` starts at 0; dismissing the dialog (`cancelResumePrompt()` → completes null) aborts the play without audio. Returning users with a local Room row resume silently (no prompt). The chosen position is seeded into Room immediately (so Continue Listening populates and the next session resumes silently). See `PlaybackManager.play()` (the `isServerResume` gate + `resumeDeferred`), `confirmResume`/`cancelResumePrompt`/`formatPosition`, and `MainActivity.observeResumePrompt()`.
- **Redundant "Now Playing" bottom-nav tab removed (2026-08-04)**: post-1.6.0 the 4th tab (`dashboardFragment` → `NowPlayingTabFragment`) was a near-empty placeholder that only re-expanded the player sheet on resume — redundant with the mini-player bar that is already the player's sole entry point. Removed the `<item>` from `bottom_nav.xml`, the `<fragment>` destination from `nav_graph.xml`, and deleted `NowPlayingTabFragment.kt` + `fragment_now_playing_tab.xml`. `setupWithNavController` now binds the remaining 3 tabs (Library | Downloads | Settings). No code change in `MainActivity` beyond a comment — back-press root-screen logic references `libraryFragment`/`authFragment` only.
- **Durable downloads vs read-ahead cache split + auto-evict-on-complete (fixed 2026-08-05)**: previously a `downloaded_books` row could be either an explicit full-book download or an invisible read-ahead cache file, with no way to tell them apart — and completion freed neither. Now: a new `durable` column (DB v4, `MIGRATION_3_4`) marks explicit downloads. (1) The Detail "Download" button (`DetailViewModel.startDownload`) passes `targetCachedUpToMs = book.duration` + `durable = true` so it grabs the WHOLE book (closes old Known Issue #2 — it previously passed `0` and capped at `downloadHours`) and is spared from completion-eviction. (2) Both completion paths — `STATE_ENDED` real-end in `AudiobookPlaybackService` and manual "Mark as Read" in `LibraryViewModel.markCompleted` — auto-delete the file + row **iff `!durable`** via the new `repository.deleteDownloadAndFile(ratingKey)`. Durable downloads persist until the user explicitly removes them. Mark-as-unread deletes nothing. (3) The latent leak where `repository.deleteDownload()` deleted the DB row but orphaned the audio file is fixed: Detail "Remove download" and the Downloads trash button now call `deleteDownloadAndFile`. The extend path in `BookDownloadWorker` preserves `existing.durable || durable` so a durable download never silently downgrades to cache on a read-ahead extend. The offline badge / Downloads screen / `buildStreamUrl` are unchanged (a durable full-book download plays fully offline as before). DEFAULT 0 on the new column means every existing read-ahead row on disk stays cache after migration — correct, since none were durable before.

### Open
1. **End-of-book crash when replaying completed book**: `ForegroundServiceStartNotAllowedException` crash still occurring in some scenarios when replaying a completed book. The 1.6.0 re-arch added `safeStartForeground()` at all three foreground call sites (`:223/:327/:474`) — this is the planned mitigation and may already resolve the replay case. **Update (1.6.3 testing):** the completion-teardown fix (`stopPlaybackAndService()` above) is almost certainly the same defect — the old real-end branch left the service half-stopped (no foreground notification, `STATE_ENDED` mid-callback), the same state a replay-of-completed-book would re-enter. Next step: replay a completed book and capture `adb logcat -v time com.plexaudiobooks:V AndroidRuntime:E *:S > crash_log.txt` (`adb` in `cmd.exe`, not PowerShell — see Coding Preferences). Review full output including lines before the fatal exception. If the crash no longer reproduces, close this issue.
2. **Download notification**: no progress notification in the notification area during download (Stage 2, not yet done).
3. **Auto-complete-on-save NOT wired (deferred)**: `autoCompleteMinutes` is stored in `SessionManager` but **the engine logic itself is not implemented** — `AudiobookPlaybackService.saveProgress()` (`:680-696`) does not call `setCompleted()` near the end; only natural `STATE_ENDED` does. So the Settings UI control is moot until the near-end auto-mark is actually written. Pre-1.6.0 gap; deferred until the re-arch is stable. (Was previously listed as "Auto-complete threshold UI not added" — that understated it.)
4. **Login flow**: occasionally slow after OAuth; home user selection sometimes requires app restart (minor, deferred).
5. **Completed section UX**: long-press to mark complete/unread is not immediately discoverable — consider swipe action or context menu in a future pass.
6. **Settings "clear cache"** nukes the whole `downloads` dir indiscriminately (durable + cache together) and does not clear the `downloaded_books` DB table. Left as-is in 1.6.2; a future pass could make it delete only non-durable files + their rows.

### Resolved in 1.6.3 (2026-08-05)
- **Streamed-book read-ahead cache now evicted on completion** (#1): see End-of-Book Workflow above. Root cause was the truncated-file branch mistaking a genuine server-stream end for a truncated local-file end whenever a partial read-ahead row existed (which streams also have). Fixed by gating that branch on `currentSourceIsLocal` and hardening the eviction guard to `if (download?.durable != true)`.
- **Tap-to-play feedback (#2)**: `PlaybackManager.play()` now sets `NowPlayingUiState.isStarting = true` before any network work so the mini-player bar appears within a frame with a "Loading…" affordance (button disabled), then clears it in a `finally`. A new `playJob` cancels a prior in-flight `play()` on a rapid re-tap so the latest tap wins and no duplicate start intent fires. `hasContent` now includes `isStarting` so `showPlayerSheet()` opens the sheet on the first tap.
- **Perpetual loading circle in chapter list fixed (#3)**: `fragment_player.xml`'s `loadingGroup` ProgressBar was `visible` by default and `PlayerSheetFragment.render()` never hid it. `render()` now sets `binding.loadingGroup.isVisible = state.book == null` so the spinner clears once the book loads.
- **Notification cover art (#4)**: `AudiobookPlaybackService` now loads the book cover into a `Bitmap` via `Glide.with(applicationContext).asBitmap()` into a `CustomTarget<Bitmap>(512,512)` (cached by thumbUrl in service fields; in-flight loads cancelled on URL change) and puts it into `MediaMetadataCompat` as `METADATA_KEY_ART` + `METADATA_KEY_ALBUM_ART` — the system MediaStyle notification / lock-screen / car / Bluetooth render art ONLY from these bitmap keys (the URI variants were un-resolved hints). `buildNotification()` also `.setLargeIcon(cachedArtBitmap)` for the dropdown thumbnail. `BigPictureStyle` deliberately NOT used (it fights `MediaStyle`'s compact actions). `clearArt()` on book switch + onDestroy.
- **Continue Listening flicker/blank on refresh fixed (#5)**: `getContinueListening()` is a JOIN of `cached_library ⋈ playback_progress`; during `refreshCachedLibrary` the JOIN can emit a transient empty list (concurrent progress saves + large inserts leak the empty intermediate), which blanked the section. `LibraryViewModel.continueListening` now `combine`s the DAO flow with the refresh signal and `scan`s, holding the last non-empty list ONLY while `ui.isLoading` (so a refresh never blanks it; a genuine empty when not refreshing — marking a book completed — passes through immediately). `distinctUntilChanged` avoids redundant `submitList` calls. *(This refresh-only guard was extended to cover playback-save races too — see Resolved in 1.6.4.)*

### Resolved in 1.6.4 (2026-08-06)
Shipped after user testing of the 1.6.3 five fixes. Two remaining defects:

- **App disappears at end of a streamed book (completion teardown race)**: the `STATE_ENDED` real-end branch tore playback down *inline* inside the ExoPlayer `onPlaybackStateChanged` listener — `saveProgress`, `setCompleted`, a suspend `deleteDownloadAndFile`, then `cancel jobs / abandonAudioFocus / stopForeground(REMOVE)` — with **no `exoPlayer.stop()` and no `stopSelf()`**. So the service was never actually stopped, just stripped of its foreground notification, while the player sat in `STATE_ENDED` mid-callback and the suspend file delete interleaved. On Android 12+ the system killed the un-foregrounded service within seconds → the app "just disappeared" at end of book (no crash dialog), streaming books the worst trigger because the delete ran there. Almost certainly the same defect as the long-standing Open Issue #1 (replay-of-completed-book `ForegroundServiceStartNotAllowedException`) — both reached the same half-stopped state. **Fix:** new `stopPlaybackAndService()` helper (cancel `stateUpdateJob`/`progressJob` → `abandonAudioFocus()` → `exoPlayer.stop()` → `stopForeground(STOP_FOREGROUND_REMOVE)` → `stopSelf()`) — the single teardown path, called from BOTH the real-end branch (after save/complete/evict/reset-to-0) and the `MediaSession.Callback.onStop()` override. Never reintroduce inline teardown in the real-end branch; go through the helper. See End-of-Book Workflow above. NOTE: the order of progress writes at completion is unchanged — `saveProgress("stopped")` (→ local Room + `/:/timeline` Plex sync) fires at the real `STATE_ENDED` position, then `repository.saveProgress(ratingKey,…,0L,endDur)` resets to 0; only the teardown *after* them moved, so progress-sync-to-Plex should be unaffected (worth confirming a finished book's server viewOffset lands at ~duration, not 0).
- **Continue Listening blanked when interacting with the mini-player (playback-save race)**: the 1.6.3 fix (#5 above) only held the list during a library *refresh* (`ui.isLoading`). But opening the mini-player / tapping in it triggers `playback_progress` saves (the service's 10s loop + `PlaybackManager.play()`'s seed-progress write), which re-emit the JOIN during a save/seek and can transiently produce `[]` — and `ui.isLoading` is `false` then, so the guard didn't apply and the section blanked (pull-to-refresh only "sometimes" brought rows back because the refresh path *was* covered). **Fix:** `LibraryViewModel.continueListening` now `.debounce(250)`s the source (collapses a rapid `[]`→pop pair into the pop) and `scan`s over `Pair<List, Boolean>`, **bridging at most one transient empty** — holding the last non-empty list for exactly one empty emission (`heldOnce` flag) so a mid-save `[]` never blanks the section, while a genuine *persistent* empty (finishing the last book) passes through on the second empty emission so the section updates for real. A plain "hold last non-empty always" would trap a stale list when the last book finishes; bridging one avoids that. `@OptIn(ExperimentalCoroutinesApi)` on the property (debounce). `combine(_uiState)` kept so the flow still re-emits on refresh-state changes (no longer used as a gate). `distinctUntilChanged` avoids redundant `submitList`.

### Resolved in 1.6.5 (2026-08-11)
- **Progress sync to Plex silently stopped working (regression from the 1.6.0 streaming-no-audio fix)**: local Room progress saved fine (Continue Listening still populated), but the `/:/timeline` sync to the Plex server was dropped for **every** book — streamed *and* downloaded. Root cause: `PlaybackManager.play()` computed `val key = playBook.mediaKey ?: ratingKey` and sent that as `EXTRA_KEY` → the service's `currentKey` → `reportProgressToPlex(key, key, …)`. After the 1.6.0 fix upgraded `playBook.mediaKey` from `null` to the first track's real **part key** (`/library/parts/12345/…`, set in `fetchBookDetail`), `key` became a part key. Then `reportProgressToPlex`'s old heuristic `val trackKey = if (key.startsWith("/library")) key else "/library/metadata/$ratingKey"` saw the part key start with `/library` and passed it through **unchanged** — so both the `ratingKey` and `key` query params sent to `/:/timeline` were part keys, which Plex can't resolve to any timeline entry → silently dropped (and logged at `Log.w`, invisible in logcat). Before the 1.6.0 fix `mediaKey` was `null` for streams, so `key` fell back to the album ratingKey — Plex would resolve that for single-file M4B, so sync happened to work; the no-audio fix improved mediaKey correctness but routed the timeline `key` through a value it was never meant to carry. **Fix:** (1) `PlaybackManager.play()` now sends `playBookWithTrack.trackRatingKey` (the first track's **ratingKey**, populated only by `fetchBookDetail` and overlaid onto the book for both the streaming AND downloaded branches — neither the `cached_library` row nor the `downloaded_books` row carries it) as `EXTRA_KEY`, falling back to the album ratingKey when there are no track children. (2) `reportProgressToPlex` drops the `startsWith("/library")` heuristic and always builds `key=/library/metadata/{ratingKey}` itself, stripping any existing prefix and passing the track ratingKey as the `ratingKey` query param. (3) The local Room `saveProgress` in the service stays keyed by the album `currentRatingKey` so Continue Listening's JOIN (which CASTs ratingKey AS TEXT against the album key) still matches. Consequence: a finished book's server viewOffset now lands at ~duration (the 1.6.4 "worth confirming" risk) — the completion branch's `saveProgress("stopped")` reports the real `STATE_ENDED` position before the local reset-to-0. See Progress Reporting above for the contract.

---

## Coding Preferences

- Concise Kotlin idioms; no unnecessary abstraction
- Suspend functions for all network/DB calls
- `Flow` for reactive UI state; `StateFlow` for ViewModels
- `withContext(Dispatchers.IO)` for all blocking work in repository
- No `continue` inside `launch {}` lambdas — use `if` blocks instead (experimental feature in Kotlin)
- `@HiltWorker` requires `PlexAudiobooksApp : Configuration.Provider` — do not revert
- Never wrap a Paging3 `RecyclerView` inside `NestedScrollView` — causes memory exhaustion crash
- `onSeekTo` in service always receives chapter-relative position — do not change to absolute without updating all seek paths
- Use `collect` (not `collectLatest`) for flows where every emission must be processed (e.g. `downloadedKeys`, `continueListening`) — `collectLatest` cancels in-flight processing and can cause missed updates
- Always use `CAST(ratingKey AS TEXT)` on both sides of Room JOIN queries — Plex ratingKeys look like integers but must be treated as strings throughout
- `adb logcat` is run in Windows `cmd.exe`, not PowerShell — PowerShell command syntax does not work
- The `BuildConfig` unresolved reference in `SettingsFragment.kt` is a cosmetic IDE error — builds succeed normally, ignore it

---

## GitHub Repository

**Repository URL:** `https://github.com/richminichiello/PlexAudiobooks`
**Branch:** `master`

### Important Note on GitHub CDN Caching
`raw.githubusercontent.com` caches aggressively. Do not rely on fetching raw GitHub URLs for active development sessions. Use the Claude Project file uploads instead.

### Key File URLs (for reference only)
| File | URL |
|------|-----|
| `PlexRepository.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/data/PlexRepository.kt` |
| `AudiobookDatabase.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/data/local/AudiobookDatabase.kt` |
| `Daos.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/data/local/Daos.kt` |
| `Entities.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/data/local/Entities.kt` |
| `AppModule.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/di/AppModule.kt` |
| `AudiobookPlaybackService.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/service/AudiobookPlaybackService.kt` |
| `BookDownloadWorker.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/service/BookDownloadWorker.kt` |
| `BookAdapter.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/ui/library/BookAdapter.kt` |
| `CompletedAdapter.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/ui/library/CompletedAdapter.kt` |
| `ContinueListeningAdapter.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/ui/library/ContinueListeningAdapter.kt` |
| `HeaderAdapter.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/ui/library/HeaderAdapter.kt` |
| `LibraryFragment.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/ui/library/LibraryFragment.kt` |
| `LibraryViewModel.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/ui/library/LibraryViewModel.kt` |
| `PlaybackManager.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/ui/playback/PlaybackManager.kt` |
| `PlayerSheetFragment.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/ui/player/PlayerSheetFragment.kt` |
| `SessionManager.kt` | `https://raw.githubusercontent.com/richminichiello/PlexAudiobooks/refs/heads/master/app/src/main/java/com/plexaudiobooks/util/SessionManager.kt` |

---

## Claude Project Setup

All source files are maintained as `.txt` uploads in the Claude Project. This is the preferred way to share code, bypassing GitHub CDN caching.

### File Naming Convention
Original filename with `.txt` appended: `LibraryFragment.kt` → `LibraryFragment.kt.txt`

### Generating Upload Files
Run from the project root in Android Studio terminal (PowerShell):

```powershell
New-Item -ItemType Directory -Path "claude_upload" -Force
$files = @(
    (Get-ChildItem -Recurse -Filter "*.kt" app\src\main\java),
    (Get-ChildItem -Recurse -Filter "*.xml" app\src\main\res\layout)
)
$files | ForEach-Object {
    Copy-Item $_.FullName "claude_upload\$($_.Name).txt"
}
$files | Compress-Archive -DestinationPath PlexAudiobooks_source.zip -Force
```

Re-upload only the files that changed. No need to regenerate everything.

### Anchor Comments
Each actively edited file should have an anchor comment on line 2:
```kotlin
// Anchor: 6/25/2026 10:00 AM ET
```
At the start of any session, ask Claude to confirm the anchor comment matches before making changes.

---

## File Quick Reference for Common Tasks

| Task | Primary Files |
|------|---------------|
| Auth / login bug | `AuthViewModel.kt`, `ServerSetupViewModel.kt`, `PlexModels.kt` |
| Playback bug | `AudiobookPlaybackService.kt`, `PlaybackManager.kt` (ui/playback), `PlayerSheetFragment.kt`, `MainActivity.kt` |
| Chapter tracking | `AudiobookPlaybackService.kt` → `startStateUpdating()`, `onSeekTo()`, `onSkipToQueueItem()` |
| Progress sync to Plex | `PlexRepository.kt` → `reportProgressToPlex()` (always builds `/library/metadata/{trackRatingKey}`), `PlexApi.kt` → `reportTimeline()`; key sourced from `AudioBook.trackRatingKey` via `PlaybackManager.sendPlayIntent` (EXTRA_KEY) → service `currentKey` |
| Resume from server (fresh install) | `PlaybackManager.kt` → `play()` (`isServerResume`/`resumeDeferred`), `confirmResume`/`cancelResumePrompt`/`formatPosition`; `NowPlayingUiState.kt` → `ResumePrompt`/`showResumePrompt`; `MainActivity.kt` → `observeResumePrompt()`/`showResumeDialog()` |
| Read-ahead / offline cache | `BookDownloadWorker.kt`, `AudiobookPlaybackService.kt` → `triggerReadAheadIfNeeded()` |
| Offline playback routing | `PlaybackManager.kt` (ui/playback) → `buildStreamUrl()` |
| Downloads (explicit full book) | `DetailViewModel.kt` → `startDownload()`, `BookDownloadWorker.kt` |
| End-of-book / completion | `AudiobookPlaybackService.kt` → `saveProgress()`, `onPlaybackStateChanged()` (STATE_ENDED), `safeStartForeground()` |
| Replay completed book | `PlaybackManager.kt` (ui/playback) → `play()` — saved-position-reset + un-complete-on-replay logic (moved from deleted `PlayerViewModel.loadBook()`) |
| Library screen layout | `LibraryFragment.kt`, `LibraryViewModel.kt`, `HeaderAdapter.kt`, `Daos.kt` |
| Continue Listening section | `HeaderAdapter.kt` → `ContinueListeningHeaderAdapter`, `ContinueListeningAdapter.kt`, `Daos.kt` → `getContinueListening()` |
| Completed section | `HeaderAdapter.kt` → `CompletedSectionAdapter`, `Daos.kt` → `getCompleted()`, `PlexRepository.kt` → `fetchLibrary()` |
| Offline badge | `BookAdapter.kt`, `LibraryFragment.kt` → `downloadedKeys` observer |
| Now Playing load/render | `PlaybackManager.kt` → `play()` (staged load) + `pollPosition()`, `PlayerSheetFragment.kt` → `render()` (collects `PlaybackManager.state`) |
| Connection / remote access | `SessionManager.kt` → `resolveActiveServerUrl()` |
| Settings | `SettingsFragment.kt`, `SessionManager.kt` |
| DB schema change | `Entities.kt`, `AudiobookDatabase.kt` (bump version + add migration) |
