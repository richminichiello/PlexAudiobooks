# PlexAudiobooks — Developer Handoff

**Version:** 1.8.10 (versionCode 57) — **STABLE / PRODUCTION-READY.** Chapter-clip Media3 architecture fully shipped: playlist-of-chapter-clips, two-phase `play()` with local Room caches (DB v5), on-device smoke tests ALL PASS (2026-09-02). All known playback bugs fixed.

> **STATUS (2026-08-17):** Both sides of the Media3 migration (Service + PlaybackManager) are Media3 in the source tree AND **COMPILE + PACKAGE** (`:app:assembleDebug` succeeded; APK at `app/build/outputs/apk/debug/PlexAudiobooks-1.7.0-debug.apk`). The **Media3 `MediaSession` is built on the RAW ExoPlayer** — book-absolute position/duration everywhere (one coordinate system; the `ChapterAwarePlayer` `ForwardingPlayer` wrapper that made the notification chapter-relative was DELETED after a smoke test found its chapter-rel↔book-abs drift broke resume/15s-30s/chapter-list/chapter-skip). `PlaybackManager` is a Media3 `MediaController` + `SessionToken` (controller-driven `setMediaItem`/`prepare`/`play`; `Player.Listener` for playback events; `updateStateFromController` reads book-absolute `controller.currentPosition` directly — no re-anchoring). All seeks (resume, seekbar, chapter-list tap, 15s/30s, next/previous chapter) are the same book-absolute `controller.seekTo(bookAbs)`; chapter skip is computed off `state.currentChapterIndex`/`positionMs` (NOT `controller.seekToNext`, which no-ops on the single-item ExoPlayer). The notification seek bar is now **book-level, not per-chapter** (accepted regression; the in-app sheet keeps chapter-relative display). The dead `ACTION_PLAY`/`EXTRA_*` constants and `sendPlayIntent` are deleted; `MainActivity` no longer builds a start Intent. The gradle wrapper EXISTS (`gradlew`/`gradlew.bat`/`gradle-wrapper.jar`, 8.10.2). To rebuild: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --console=plain --no-daemon :app:assembleDebug` (bash `./gradlew`; `cmd.exe /c gradlew.bat …` swallows output). Build warnings only: `onUpdateNotification(MediaSession)` deprecated-in-Java (deliberate — keep), `debounce` wants `@OptIn(FlowPreview)` (1.6.4 pre-existing). **REMAINING = ON-DEVICE SMOKE TEST** (see the risk flags in memory `media3-chapterawareplayer-removed.md`). See the "1.7.0 Media3 Migration" + "Build" sections below.

**Language:** Kotlin
**Min SDK:** 26 | **Target SDK:** 35 | **Compile SDK:** 35

---

## Project Overview

Native Android audiobook player for Plex Media Server. Authenticates via Plex OAuth, browses music/audiobook libraries, plays M4B files with chapter navigation, tracks and syncs progress back to Plex, and supports offline playback via sliding-window read-ahead cache.

---

## Tech Stack

| Layer           | Library                                                                                                                                              |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| UI              | MVVM, ViewBinding, Navigation Component (Safe Args)                                                                                                  |
| DI              | Hilt 2.52                                                                                                                                            |
| DB              | Room 2.6.1 (version 2, one migration applied)                                                                                                        |
| Network         | Retrofit 2.11.0 + Gson + OkHttp 4.12.0                                                                                                               |
| Playback        | ExoPlayer (Media3 1.5.0)                                                                                                                             |
| Media Session   | Media3 1.5.0 — `MediaSessionService` + `MediaSession` built on the raw ExoPlayer (book-absolute position/duration; book-level notification seek bar) |
| Paging          | Paging3 3.3.5                                                                                                                                        |
| Images          | Glide 4.16.0                                                                                                                                         |
| Auth storage    | EncryptedSharedPreferences                                                                                                                           |
| Background work | WorkManager 2.10.0 (Hilt-integrated)                                                                                                                 |
| Async           | Coroutines 1.9.0 + Flow                                                                                                                              |

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

**Version 5.** Migrations: 1→2 adds `completed` to `cached_library`; 2→3 adds `shelved`; 3→4 adds `durable` to `downloaded_books`; **4→5 adds `cached_chapters` + `book_detail_cache`** (the chapter/detail local caches — see Two-Phase Play below).

### Tables
| Table | Entity | Purpose |
|-------|--------|---------|
| `playback_progress` | `PlaybackProgressEntity` | Local resume position; synced to Plex |
| `downloaded_books` | `DownloadedBookEntity` | Local file path, `downloadedUpToMs`, `durable` flag |
| `cached_library` | `CachedLibraryEntity` | Library snapshot; `completed` + `shelved` flags |
| `cached_chapters` | `CachedChapterEntity` | **NEW (v5)** — per-book chapter list, write-once-read-forever |
| `book_detail_cache` | `BookDetailCacheEntity` | **NEW (v5)** — per-book stream part key, track ratingKey, duration, thumb path |

### Why the two new tables exist (the "why" for P1)

**`cached_chapters`**: Chapters are immutable per book — they never change once the M4B is on the server. Previously they were re-fetched from the server on EVERY `play()` via a 3-call chain (album → children → per-track `includeChapters=1`), making "continue book" slow and the chapter list appear/disappear depending on network health. Now: first `fetchBookDetail()` writes the rows; every subsequent `play()` reads them from Room instantly. The chapter list is stable regardless of connectivity.

**`book_detail_cache`**: The stream part key (`mediaKey`), full part-key list, track ratingKey (for `/:/timeline`), and true summed duration are NOT in `cached_library` (album rows have no parts). They're only known after `fetchBookDetail()`. This table caches them separately from `cached_library` — which gets **wiped and rebuilt on every library refresh** — so playback detail survives. Streaming books resume instantly after the first play because the stream URL comes from this cache, not the network.

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

## Playback Architecture (1.8.x — chapter-clip playlist)

**Core design shift in 1.8.0:** the old "one ExoPlayer playing the whole book" model is replaced by a **playlist of per-chapter MediaItems**. Each `MediaItem` in the playlist is a thin `ClippingConfiguration` scoped to one chapter's `[startMs, endMs]` on the same underlying stream/file URI. The raw ExoPlayer inside `AudiobookPlaybackService` therefore sees each chapter as its own item — the notification subtitle and lock-screen seekbar are natively chapter-relative (no wrapper, no manual position math on the playback thread).

**Book-absolute position** (needed for Room progress + `/:/timeline` sync + read-ahead targeting) is reconstructed at read time: `chapters[exoPlayer.currentMediaItemIndex].startMs + exoPlayer.currentPosition`. This is the single translation point in the whole app. Every consumer of `state.positionMs` sees book-absolute; every consumer of the notification/chapter list sees chapter-relative. Degraded mode (no chapter data) is a single-item playlist spanning the whole book — structurally identical to plain continuous playback.

**Seek contract:** ALL seek paths (resume, seekbar, 15s/30s, next/previous chapter, chapter list tap) use `controller.seekTo(chapterIndex, chapterRelativeMs)` on the raw controller, or `seekToNext()/seekToPrevious()` (which NO-OP on a single-item playlist — hence the explicit chapter-index computation in `PlaybackManager`). See `PlaybackManager.seekAbsolute()`, `nextChapter()`, `previousChapter()`.

**Chapter loading (1.8.1 — companion object REMOVED):** the old `pendingChapters` in-process relay is gone. `AudiobookPlaybackService.loadChaptersIfNeeded()` reads from Room (`repository.getCachedChapters()`) once per session, called from `onAddMediaItems()` (first chance) and again from `STATE_READY` (back-stop). This decouples the service from the manager process — if the service restarts alone (system-initiated after process death), chapters are still available.

**Progress reporting:** `saveProgress()` every 10s writes Room + `GET /:/timeline` with `hasMDE=1`. Book-absolute position derived as described above. Duration falls back to `currentChapters.last().endMs` when `exoPlayer.duration` is -1 (common before full buffer).

**End-of-book:** `STATE_ENDED` fires only when the **final** chapter clip finishes (Media3 auto-advances between chapters — no truncated-file disambiguation needed anymore). On end: saveProgress → markCompleted → evict non-durable cache → `stopPlaybackAndService()`.

**Phone call:** pauses on `AUDIOFOCUS_LOSS`, resumes on `AUDIOFOCUS_GAIN`. The `pausedForFocusLoss` flag is reset ONLY when `chapterIndex == 0` (fresh play session) — never on subsequent item resolutions, so a mid-call playlist rebuild doesn't clear the flag.

### ForegroundServiceStartNotAllowedException (Android 12+, pre-existing mitigation)
`safeStartForeground()` wraps `startForeground()` in try/catch at all three call sites (`onPlay`, `playBook`, `onIsPlayingChanged`) — logs instead of crashing when the app is background-restricted. **Do not remove.**

### stopPlaybackAndService() (1.6.4, still critical)
Single teardown path: cancel jobs → `abandonAudioFocus()` → `exoPlayer.stop()` → `stopForeground(REMOVE)` → `stopSelf()`. Called from both natural end-of-book and `MediaSession.Callback.onStop()`. **Never tear down inline in the ExoPlayer listener.**

---

## Offline Caching / Read-Ahead Architecture

### Design: Sliding Cache Window
The app maintains a local audio file for each book that covers a window of audio ahead of the current playback position. The window size is controlled by `SessionManager.downloadHours` (default `4`, hardcoded in `SessionManager.kt:147`; the Settings picker offers `1, 2, 3, 4, 6, 8` hours — note 5 and 7 are absent from the picker).

### How It Works
1. **On every play** (first and subsequent): `triggerReadAheadIfNeeded()` is called once per session from `STATE_READY` (not `onStartCommand`). It computes `targetCachedUpToMs = (bookAbsPositionMs + downloadHours * 3600 * 1000L).coerceAtMost(durationMs)`. If the existing `download.downloadedUpToMs >= targetCachedUpToMs` (window already covers the playhead), it skips. Otherwise it enqueues `BookDownloadWorker.buildRequest(...)` with that target.
2. **Worker extend path** (single-file books only — `partKeys.size == 1 && existingDownload != null && file exists`): `BookDownloadWorker` sends a `Range: bytes=N-` HTTP request to Plex to fetch only the missing bytes, appends them to the existing file, updates `downloadedUpToMs` in the DB.
3. **Worker fresh path** (multi-part books, or no existing file): computes `maxBytes` from `desiredMs`, where `desiredMs` is `targetCachedUpToMs` **only if `> 0`**; if `targetCachedUpToMs == 0` it falls back to `downloadHours * 3600 * 1000L` from byte 0.
4. **Offline playback**: `PlaybackManager.buildStreamUrl()` returns a `file://` path when the resume position is within the cached window: `safeLocal = cachedUpTo <= 0 && positionMs == 0L || cachedUpTo > 0 && positionMs <= cachedUpTo`. When `downloadedUpToMs` is `0`/unknown, the local file is used only from position 0; otherwise it streams so ExoPlayer never seeks past the end of a truncated local file.
5. **WorkManager network constraint**: `setRequiredNetworkType(NetworkType.CONNECTED)` on every `buildRequest()` — extend jobs queue silently when offline and fire on reconnect.

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

## Now Playing Load Sequence (1.8.x — two-phase play)

`PlaybackManager.play()` (single entry point for Continue Listening, Detail, Downloads) follows a two-phase pattern:

**Phase 1 — Instant local start (fast path):**
1. Read Room in order: `detailCache` (book_detail_cache) → `download` (downloaded_books) → `cached` (cached_library) → merged into a display `AudioBook`
2. Chapters read from Room (`cached_chapters`) — no network call needed
3. `resolveAndRefreshServerUrl()` only fires if this is a **streaming** book AND we have no stored `serverUrl` — a background refresh, never blocking
4. `_state` emitted with full data → mini-player shows cover art immediately (from Glide disk cache or cached thumbPath)
5. `setMediaItems()` + `controller.prepare()` + `controller.play()` — audio starts within one frame

**Phase 2 — Background enrichment (after `setMediaItems` fires):**
6. `fetchBookDetail()` runs in a background coroutine to refresh `cached_chapters` and `book_detail_cache` (Room auto-persists; no UI action needed)
7. If the chapter list we started with was empty and the fetch returns chapters, `launchEnrichment()` hot-swaps the playlist at the current position so the chapter list appears without restarting audio

**When the fallback kicks in:** if `detailCache` is missing AND the book is not downloaded (no local file) AND there's no stored `serverUrl` — the first-ever play of a book — we fall back to the old behavior (`fetchBookDetail` synchronously before start). The next play of that book hits the fast path.

**Mini-player stability (1.8.1):** MainActivity holds `lastDisplayBook` across `state.book == null` gaps so the bar never collapses between sheet dismissal and book switch; Glide reloads cover art only when `book.ratingKey` changes.

**PlayerSheetFragment:** reads `state.chapters` directly. `state.book != null` triggers immediate render; `binding.loadingGroup` clears when `state.book` arrives. Chapter list is always populated once chapters are in Room.

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
- **Playback**: M4B chapter extraction (`includeChapters=1` per track), chapter navigation, variable speed (0.5–2.0×), skip forward/back (configurable). The in-app player sheet seekbar is chapter-relative (derived locally from book-absolute `positionMs` + the chapter list); the system notification seek bar is book-level (1.7.0-final: the chapter-relative `ChapterAwarePlayer` wrapper was removed for stability).
- **Notification + car display**: book-level progress bar + book title (Media3 MediaStyle on the raw ExoPlayer). The chapter title shows only in the in-app sheet.
- **Audio focus**: pauses on phone call/nav, resumes on focus regain; `ForegroundServiceStartNotAllowedException` caught safely on Android 12+
- **Plex progress sync**: `/:/timeline` with `hasMDE=1`, resumes from server after reinstall
- **Sliding read-ahead cache**: configurable hours (default 4; Settings picker offers 1/2/3/4/6/8), extends automatically as user progresses, uses HTTP Range requests to append only missing bytes, WorkManager queues extend jobs offline and fires on reconnect
- **Local-first playback**: streams from local file when resume position is within the cached window (`safeLocal = cachedUpTo<=0 && positionMs==0L || cachedUpTo>0 && positionMs<=cachedUpTo`), falls back to server stream when beyond cache
- **Truncated file detection**: when ExoPlayer hits end of a partial local file, service switches to streaming seamlessly rather than treating it as end-of-book
- **Now Playing fast load**: cover art renders immediately from Glide disk cache; chapter-relative seekbar waits for chapter data before showing
- **Remote connection**: parallel server URL probing with fallback to relay
- **Settings**: skip times, download hours, download location, storage display, clear cache, sort/view preferences, sign out

---

### 1.7.0 Media3 Migration (COMPLETE)
All Media3 migration work is finished and verified on-device. The chapter-clip playlist architecture (playback as a list of chapter-clipped MediaItems) is now the production path. The old `ChapterAwarePlayer` wrapper is gone. `:app:assembleDebug` builds cleanly.

### 1.8.x Chapter Caching & Two-Phase Play (COMPLETE — 1.8.10 / code 57)
**Goal:** Playing a book (especially a resumed book) should start in <1s with no network dependency, using locally cached chapters and stream URLs.

**What was built:**
- `cached_chapters` (Room v5): immutable per-book chapter list, written once on first `fetchBookDetail()` — read forever after from Room
- `book_detail_cache` (Room v5): stream part key (`mediaPartKey`), full part-key list, track ratingKey (for `/:/timeline`), duration, thumb path — cached separately from `cached_library` because `fetchLibrary()` wipes that table on refresh
- `PlaybackManager.play()` two-phase: **Phase 1** all local (Room), instant state emission + `setMediaItems` + `play()`; **Phase 2** background `fetchBookDetail()` refresh, hot-swaps chapters into the playlist if the cached copy was stale
- `AudiobookPlaybackService.loadChaptersIfNeeded()`: reads chapters from Room (replaces the old `pendingChapters` companion object) — service no longer depends on in-process relay

**Verified on-device:** book completion, replay-from-completed, seek/skip/chapter navigation, `/:/timeline` sync, read-ahead triggering, cover art stability, phone-call pause/resume, mini-player persistence.

### Service side
`AudiobookPlaybackService` is a **`MediaSessionService`** (Media3), not `MediaBrowserServiceCompat`. It builds `MediaSession.Builder(this, exoPlayer)` (the **RAW** ExoPlayer) with a `GlideBitmapLoader` (`BitmapLoader`) + `MediaSession.Callback`. Position/duration are **book-absolute** — the session reads the raw `exoPlayer.currentPosition`/`duration` polymorphically. (The earlier-draft `ChapterAwarePlayer` `ForwardingPlayer` that made the notification chapter-relative was DELETED — see the 1.7.0-final smoke-test fix below; the notification seek bar is now book-level.)

**Start path = controller-driven.** `MainActivity` no longer calls `startPlayback()`. `PlaybackManager` (the controller) calls `controller.setMediaItem(thinItem)` + `prepare()` + `play()` over the Media3 session. `thinItem` is a `MediaItem` with `mediaId = ratingKey` and `mediaMetadata.extras` (a `Bundle`) carrying `X_RATING_KEY`/`X_STREAM_URL`/`X_TITLE`/`X_AUTHOR`/`X_THUMB_URL`/`X_START_POSITION_MS`/`X_SPEED`/`X_PART_KEYS`/`X_DURATION_MS`/`X_TIMELINE_KEY`. The service's `MediaSession.Callback.onAddMediaItems(controllerInfo, mediaItems)` resolves that thin item (no playable URI) into the real item (stream/file URI + display metadata). The manifest `<service>` intent-filter action is `androidx.media3.session.MediaSessionService`.

**Start position is applied on the RAW exoPlayer in the first-READY handler** (`applyPendingStart` gate), read from `X_START_POSITION_MS` (book-absolute). We override `onAddMediaItems` (returns `List<MediaItem>`, no position semantics) rather than `onSetMediaItems`.

**Audio focus** is the service's explicit job (`MediaSessionService` does NOT manage it): `requestAudioFocus()` is called in the first-READY `applyPendingStart` block. **No manual `stopForeground`/`STOP_FOREGROUND`** — `MediaSessionService` owns its own notification; `stopPlaybackAndService()` keeps `exoPlayer.stop()` + `stopSelf()` and drops the manual foreground calls. `GlideBitmapLoader` supplies Media3's `BitmapLoader`; cover art is resolved from `MediaItem.MediaMetadata.artworkUri`.

**Chapter data:** `pendingChapters` (companion object) is drained once at play start by `loadPendingChapters()` into `currentChapters`; the service keeps it for `saveProgress`'s `currentChapters.last().endMs` duration fallback + `handleStateEnded`/read-ahead. The service does NOT track a chapter index or participate in seek/skip arbitration — that lives entirely in `PlaybackManager`.

### `PlaybackManager`
Media3 `MediaController` + `SessionToken`. The legacy `MediaBrowserCompat`/`MediaControllerCompat`/`transportControls` and the deleted `EXTRA_ABSOLUTE_POSITION` read are gone.
- **Connection:** `MediaController.Builder(appContext, SessionToken(appContext, ComponentName(appContext, AudiobookPlaybackService))).setListener(MediaController.Listener).buildAsync()` → `ListenableFuture<MediaController>`. `ensureControllerAwaited()` is a hand-rolled `suspendCancellableCoroutine` + Guava `Futures.addCallback` await with an **inline `directExecutor`** (`java.util.concurrent.Executor { it.run() }`, NOT Guava's `MoreExecutors`; no `kotlinx-coroutines-guava` dep). The controller handoff (`addListener(playerListener)` + `pollPosition()` seed) runs on the MAIN thread. `release()` is single-path (`releaseFuture` if the future is around, else bare `controller.release()`).
- **Callbacks:** `MediaController.Listener` (interface — `object : MediaController.Listener { }`, no constructor parens) carries session-level events only (`onDisconnected`/`onError`). Playback events come via a **`Player.Listener`** on the resolved controller (controller IS-A `Player`).
- **`play()`** semantics preserved: resume prompt / un-complete-on-replay / un-shelve-on-play / `resolveAndRefreshServerUrl` / `buildStreamUrl` / `isStarting` feedback / `playJob` re-entrancy. The terminal `sendPlayIntent` is **replaced** by a **thin `MediaItem`** (`setMediaId(ratingKey)` + `MediaMetadata` with title/artist/albumTitle/subtitle/artworkUri + extras(Bundle) carrying the X_*), then `ctrl.setMediaItem` + `prepare()` + `play()`. `AudiobookPlaybackService.pendingChapters = finalChapters` is set first.
- **Position contract (book-absolute):** `controller.currentPosition` IS book-absolute (session on raw ExoPlayer), so `updateStateFromController()` reads it directly into `state.positionMs` — no re-anchoring. `currentChapterIndex`/`chapterDurationMs` are derived from `absPos` + the chapter list purely to drive the in-app sheet's local chapter-relative seekbar + chapter-list highlight.
- **Transport:** `togglePlayPause`→`controller.play()`/`pause()`; `seekAbsolute(abs)`→`controller.seekTo(abs.coerceIn(0, bookDurationMs))` (book-absolute, no relativization); `skipBy`→`seekAbsolute(positionMs + delta)`; `nextChapter`/`previousChapter`→ compute `chapters[idx±1].startMs` off `state.currentChapterIndex`/`positionMs` (previous chapter: >3s into current restarts it, else prior) and call `seekAbsolute(...)` — NOT `controller.seekToNext/Previous` (no-op on single-item ExoPlayer), NOT a service skip. `setSpeed`→`controller.setPlaybackSpeed`; `stop`→`controller.stop()`.
- **`MainActivity`:** the dead `ACTION_PLAY`/`EXTRA_*` companion constants AND the now-unused `import android.content.Intent` are **deleted**. `stopPlaybackAndExit` = `playbackManager.stop()` + `finishAffinity()`.
- **Deferred:** `androidx.media:media:1.7.0` (`app/build.gradle`) is now referenced only by comments — removable in a follow-up.

### Build (wrapper GENERATED; **`:app:assembleDebug` BUILDS SUCCESSFULLY 2026-08-15 → `PlexAudiobooks-1.7.0-debug.apk`; remaining = on-device smoke test**)
The repo NOW HAS `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` (gradle 8.10.2) — committed by the `gradle wrapper` step on 2026-08-15, fixing the long-standing "no gradlew" gap. A normal build is:
```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --console=plain --no-daemon :app:assembleDebug
```
Run via the bash `./gradlew` in the repo root — `cmd.exe /c "gradlew.bat …"` swallows the output and only prints the cmd banner. The JBR java is `C:\Program Files\Android\Android Studio\jbr\bin\java.exe` (JDK 17; gradle 8.10.2 needs 17+). If the wrapper is ever missing: download `gradle-8.10.2-bin.zip` (~130MB) to `C:\Temp\pa_gradle_dl` (`curl -L --max-time 540 -o … https://services.gradle.org/distributions/gradle-8.10.2-bin.zip`), unzip, `JAVA_HOME=… "C:\Temp\pa_gradle_dl\gradle-8.10.2\bin\gradle.bat" wrapper --gradle-version 8.10.2`. (No system/choco/scoop gradle exists; Android Studio ships only the Tooling API jars, not a runnable `gradle`.)

**Build pass 1 (2026-08-15) FAILED in `:app:compileDebugKotlin` — 6 errors, all FIXED, awaiting pass 2:**
1. `GlideBitmapLoader.kt` "Unclosed comment at EOF" — a `` `image/*` `` backticked code span inside a `/** */` KDoc. **Kotlin block comments nest and lex before markdown**, so the `/*` opened a nested comment; the KDoc's `*/` only closed the nested one, leaving `/**` unclosed. Fix: rephrased to drop the `/*` literal. **Codebase rule: never write a literal `/*` inside a KDoc, even backticked.** (This file was written in the prior session and never built — masked by the manager's `EXTRA_ABSOLUTE_POSITION` error.)
2. `AudiobookPlaybackService.kt` `object : MediaSession.Callback() { }` — `MediaSession.Callback` is an **interface** (verified via `javap` on the media3-session 1.5.0 AAR), not a class. Fix: `object : MediaSession.Callback { }` (no constructor parens; `MediaController.Listener` is also an interface — same pattern).
3. `AudiobookPlaybackService.kt` `onSkipToPrevious`/`onSkipToNext` "overrides nothing" — `MediaSession.Callback` has **no** skip hooks in Media3 1.5.0. Fix (at the time): moved chapter skip to `ChapterAwarePlayer` overrides (`seekToNext`/`seekToPrevious` + `getAvailableCommands` adding `COMMAND_SEEK_TO_NEXT/PREVIOUS`) delegating to service `seekToNextChapter`/`seekToPreviousChapter` (wired in `onCreate` via `setChapterNavigation`). **SUPERSEDED in 1.7.0-final:** `ChapterAwarePlayer` was deleted; chapter skip is now a plain book-absolute `controller.seekTo(chapter.startMs)` in `PlaybackManager` (no `seekToNext`/`seekToPrevious`, no service skip wiring).
4. `PlaybackManager.kt` `this@ControllerManager.…` "Unresolved label" — the class is `PlaybackManager`. Fix: `this@PlaybackManager`.
5. `PlaybackManager.kt` `ensureControllerAwaited` "Missing return statement" — bare trailing expression after a `withContext { return@withContext }` was ambiguous. Fix: `return withContext(Dispatchers.Main) { … }` yielding `null` (superseded) or `ctrl` (adopted).
6. (Pre-emptive) `MoreExecutors.directExecutor()` → inline `directExecutor` field — removes a Guava class assumption; the build confirmed `Futures`/`ListenableFuture`/`FutureCallback` are all that's needed.

Alternatively, open the project once in Android Studio (it auto-generates the wrapper if missing).

---

## Known Issues / Pending

### Resolved
- **Streaming books played no audio (1.6.0 regression, fixed 2026-08-04)**: tapping a non-downloaded book → Detail → Play populated the mini-player (state had a `book`) but produced no audio — `play()` bailed before `sendPlayIntent`. Root cause: `play()` built `displayBook` from the `cached_library` row, whose `mediaPartKey` is `null` (the library `/all` endpoint returns album-level items with no parts), so `buildStreamUrl()` hit `partKey = book.mediaKey ?: return null` and returned null → `play()` returned at the "Could not build a stream URL" branch before sending the start intent. Downloaded books worked because their `file://` branch never reads `mediaKey`. Fix: `play()` now keeps the full `AudioBook` from the `fetchBookDetail()` it already calls (`Pair<AudioBook, List<Chapter>>`) and upgrades `displayBook` with the real part key (`mediaKey`/`allPartKeys`) when the cached row lacks them, before `sendPlayIntent`/`buildStreamUrl`/`_state.value.book`. No new network call; downloaded path untouched. See `PlaybackManager.kt:219-247`. (Logs tagged `AudiobookService` were filtered by Samsung OneUI in both captures; root cause was pinned by code trace.)
- **Fresh install started books from the beginning instead of resuming (fixed 2026-08-04)**: on a brand-new install (empty Room `playback_progress`), tapping Play on a book with server-saved progress started at position 0. Root cause: `PlaybackManager.play()` derived `rawPosition` only from `repository.getProgress(ratingKey)` (Room) — on a fresh install that returns `null` → `0L`. The server-saved offset was already available in `CachedLibraryEntity.viewOffset` (parsed from `/all`'s `viewOffset` during `fetchLibrary`) but `play()` ignored it. Fix: `play()` now reads the cached row's `viewOffset` as a fallback when there is no local Room row; when this server-fallback is the position source (`isServerResume`), it emits `showResumePrompt` on `NowPlayingUiState` and **suspends** the play coroutine on a `CompletableDeferred<Boolean?>` while `MainActivity` shows a "Resume from {Hh Mm}" / "Start from beginning" dialog. `confirmResume(true)` resumes from the server position; `confirmResume(false)` starts at 0; dismissing the dialog (`cancelResumePrompt()` → completes null) aborts the play without audio. Returning users with a local Room row resume silently (no prompt). The chosen position is seeded into Room immediately (so Continue Listening populates and the next session resumes silently). See `PlaybackManager.play()` (the `isServerResume` gate + `resumeDeferred`), `confirmResume`/`cancelResumePrompt`/`formatPosition`, and `MainActivity.observeResumePrompt()`.
- **Redundant "Now Playing" bottom-nav tab removed (2026-08-04)**: post-1.6.0 the 4th tab (`dashboardFragment` → `NowPlayingTabFragment`) was a near-empty placeholder that only re-expanded the player sheet on resume — redundant with the mini-player bar that is already the player's sole entry point. Removed the `<item>` from `bottom_nav.xml`, the `<fragment>` destination from `nav_graph.xml`, and deleted `NowPlayingTabFragment.kt` + `fragment_now_playing_tab.xml`. `setupWithNavController` now binds the remaining 3 tabs (Library | Downloads | Settings). No code change in `MainActivity` beyond a comment — back-press root-screen logic references `libraryFragment`/`authFragment` only.
- **Durable downloads vs read-ahead cache split + auto-evict-on-complete (fixed 2026-08-05)**: previously a `downloaded_books` row could be either an explicit full-book download or an invisible read-ahead cache file, with no way to tell them apart — and completion freed neither. Now: a new `durable` column (DB v4, `MIGRATION_3_4`) marks explicit downloads. (1) The Detail "Download" button (`DetailViewModel.startDownload`) passes `targetCachedUpToMs = book.duration` + `durable = true` so it grabs the WHOLE book (closes old Known Issue #2 — it previously passed `0` and capped at `downloadHours`) and is spared from completion-eviction. (2) Both completion paths — `STATE_ENDED` real-end in `AudiobookPlaybackService` and manual "Mark as Read" in `LibraryViewModel.markCompleted` — auto-delete the file + row **iff `!durable`** via the new `repository.deleteDownloadAndFile(ratingKey)`. Durable downloads persist until the user explicitly removes them. Mark-as-unread deletes nothing. (3) The latent leak where `repository.deleteDownload()` deleted the DB row but orphaned the audio file is fixed: Detail "Remove download" and the Downloads trash button now call `deleteDownloadAndFile`. The extend path in `BookDownloadWorker` preserves `existing.durable || durable` so a durable download never silently downgrades to cache on a read-ahead extend. The offline badge / Downloads screen / `buildStreamUrl` are unchanged (a durable full-book download plays fully offline as before). DEFAULT 0 on the new column means every existing read-ahead row on disk stays cache after migration — correct, since none were durable before.

### Open
1. ~~End-of-book crash when replaying completed book~~ → **CLOSED (on-device verified, 1.8.7)**: The `stopPlaybackAndService()` helper eliminated the crash. Confirmed by replaying a completed book on-device.
2. **Download notification**: no progress notification in the notification area during download (Stage 2, not yet done).
3. **Auto-complete-on-save NOT wired (deferred)**: `autoCompleteMinutes` is stored in `SessionManager` but the engine logic is not implemented. `saveProgress()` only marks complete on natural `STATE_ENDED`. The Settings UI control is a no-op until implemented.
4. **Login flow**: occasionally slow after OAuth; home user selection sometimes requires app restart (minor, deferred).
5. **Completed section UX**: long-press to mark complete/unread is not immediately discoverable — consider swipe action or context menu in a future pass.
6. **Settings "clear cache"** nukes the whole `downloads` dir indiscriminately (durable + cache together) and does not clear the `downloaded_books` DB table.
7. **Multi-file books read-ahead**: read-ahead extend (`Range: bytes=N-`) works only for single-file M4B books. Multi-file books fall back to full-file download from byte 0.

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
- Never wrap a Paging3 `RecyclerView` inside `NestedScrollView` — memory exhaustion crash
- Use `collect` (not `collectLatest`) for flows where every emission must be processed — `collectLatest` cancels in-flight work
- Always `CAST(ratingKey AS TEXT)` on Room JOINs — Plex ratingKeys are strings
- `adb logcat` via `cmd.exe`, not PowerShell
- `BuildConfig` unresolved reference in SettingsFragment.kt is a known cosmetic IDE error — ignore
- **Mini-player visibility is `state.hasContent` — do not gate on `book != null` alone**; the bar must survive during `isStarting` and after sheet dismissal

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
