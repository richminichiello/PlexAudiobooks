# PlexAudiobooks — Navigation & Playback Re-Architecture Plan

## Context

The app's navigation and playback are **screen-centric** when they should be **state-centric**, producing the bugs the user feels daily:

1. **No way back to Now Playing.** `PlayerFragment` is a leaf whose only exit is `btnBack → navigateUp()` (`PlayerFragment.kt:196`). Audio keeps playing in the service but there's no persistent path back to the controls.
2. **Player missing info / play sometimes dead / playback dies and can't restart without force-quit.** Root cause: the `MediaController` is **fully recreated and torn down on every fragment `onStart`/`onStop`** (`PlayerFragment.kt:76-83, 87-116`); nothing persists it. The service is `START_NOT_STICKY` (`AudiobookPlaybackService.kt:181`) started via plain `startService` (not `startForegroundService`, `MainActivity.kt:102`), so a system-killed service has no reliable resurrection path and a returning fragment has no controller to talk to. Also: `playBook`/`onPlay` call `startForegroundNotification()` **unprotected** (`AudiobookPlaybackService.kt:327, 223`) — crashes on Android 12+ from background.
3. **Continue Listening flicker / books "flash and disappear".** Root cause: `fetchLibrary()` runs `clearAll()` / `insertAll()` / per-key `setCompleted()` as **three+ separate non-transactional DAO writes** (`PlexRepository.kt:297-303`). Each write re-triggers the `getContinueListening()` Flow; the first (`clearAll`) empties `cached_library`, so the INNER JOIN emits an **empty list** the UI collapses on (`Daos.kt:96-109`, `HeaderAdapter.kt:56-66`).
4. **Downloads / Settings buried** in the Library toolbar overflow — not reachable from Player/Detail.
5. **No "put back on shelf"** — a book is only ever in-progress or completed.
6. **Fragile Activity back-press** (`MainActivity.kt:46-73`); half-authed dead state; sign-out doesn't stop playback; re-onboarding orphans the back stack.

### Decisions locked with the user
- **Full re-architecture, single codebase to debug at the end.**
- **Player = overlay/sheet** over the current tab; swipe-down/back dismisses; NOT on the nav back stack.
- **Shelved** = hidden only from Continue Listening; stays in main browse; resume preserved; returns to Continue Listening on next play.
- **Bottom nav = 4 tabs**: Library (label) | Now Playing (label) | Downloads (↓ icon) | Settings (⚙ icon).
- **Stay on `MediaSessionCompat`** (not Media3) — preserve chapter-relative notification progress + seek contract + end-of-book logic.

### Intended outcome
One persistent `PlaybackManager` owns playback state at app scope; a mini-player + bottom nav give global entry points; the player is a deterministic sheet; Continue Listening never flickers and supports a "shelf" state; the back stack is consistent everywhere.

---

## Architecture overview

### New components
- **`PlaybackManager`** (`@Singleton`, `ui/playback/PlaybackManager.kt`) — owns a persistent `MediaControllerCompat` bound to `AudiobookPlaybackService`'s session, surviving fragment lifecycle. Exposes `StateFlow<NowPlayingUiState>` (book, cover, chapter title, position, chapter duration, book duration/remaining, speed, isPlaying, isBuffering, chapters, playbackError). Owns one `play(ratingKey, startPositionMs?)` entry point used by all callers, delegating the actual start to the service via `startForegroundService` (not `startService`). Reposition polling moves here from `PlayerFragment`.
- **`NowPlayingUiState`** — the single source of truth consumed by mini-player, expanded player, and fragments.
- **`MiniPlayerView` + `view_mini_player.xml`** — persistent bar above bottom nav in `activity_main.xml`; tap expands the player sheet.
- **`PlayerSheetFragment`** (replaces `PlayerFragment`) or a `BottomSheetDialogFragment` hosted by `MainActivity` — the expanded Now Playing UI, driven entirely by `PlaybackManager` state (no per-entry `loadBook` re-probe, no fresh `MediaBrowser`).

### Layering
```
UI (Fragments/Views)  ──collect──►  PlaybackManager (singleton)  ──MediaController──►  AudiobookPlaybackService
   │                                      │ (also uses)                                    │
   └─ PlexRepository / Room ────────────►  PlexRepository                                   └─ repository.saveProgress / setCompleted
   (library, progress, download)         (play(), buildStreamUrl once per play)
```
`PlexRepository` keeps its role (data/network), but `resolveAndRefreshServerUrl` + `buildStreamUrl` are called by `PlaybackManager.play()` only when actually starting a book — not on every screen entry.

---

## Implementation phases (single codebase, build/test between phases)

### Phase 0 — Safe sanity checks & scaffolding (no behavior change)
- Read-confirm `setCompleted`/`getCompletedKeys` (`Daos.kt:62-66`), `ContinueListeningItem` (`Daos.kt:117-129`), `AppModule` bindings.
- Create empty `PlaybackManager`, `NowPlayingUiState`, `view_mini_player.xml` shells. Do not wire yet.
- **Build + smoke test** that the app still compiles and launches unchanged.

### Phase 1 — Data layer: shelf state + flicker fix
**Entity** (`Entities.kt:30-45`): add `val shelved: Boolean = false` to `CachedLibraryEntity`.
**Migration** (`AudiobookDatabase.kt`): bump `version = 3`; add
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE cached_library ADD COLUMN shelved INTEGER NOT NULL DEFAULT 0")
  }
}
```
Register in `AppModule.kt:118` → `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`. **Keep `fallbackToDestructiveMigration()` below the migrations** (note it eats resume positions if a migration is missed — flag in commit message; leave as-is this pass).

**DAO** (`Daos.kt`):
- `getContinueListening()` (`Daos.kt:96-109`): add `AND (cl.shelved = 0 OR cl.shelved IS NULL)` to the WHERE.
- `CachedLibraryDao`: add `setShelved(ratingKey, shelved)` (`UPDATE cached_library SET shelved = :shelved WHERE ratingKey = :ratingKey`) and `getShelvedKeys(): List<String>`.
- Add `shelved` to `ContinueListeningItem` projection (`COALESCE(cl.shelved, 0) AS shelved`).
- **Decide paged queries**: per user decision (shelved stays visible in browse) → leave `getPaged*` (`Daos.kt:74-90`) unchanged (still filter only `completed`).

**Repository** (`PlexRepository.kt`):
- Add `setShelved(ratingKey, shelved)` wrapper.
- **Flicker fix** — wrap the refresh writes in a single transaction so the Flow emits once, atomically. Add a `@Transaction suspend fun refreshCachedLibrary(completedKeys, shelvedKeys, fresh)` to `CachedLibraryDao` (or use `db.withTransaction {}` in the repo) doing: `clearAll() → insertAll(fresh) → re-apply completed keys → re-apply shelved keys` as **one atomic unit**. Update `fetchLibrary()` (`PlexRepository.kt:277-312`) to snapshot `getShelvedKeys()` alongside `getCompletedKeys()` and route through the transactional method.
- Optional hardening: expose `continueListening` from `LibraryViewModel` via `.distinctUntilChanged { old, new -> old.map{it.ratingKey} == new.map{it.ratingKey} && old.zip(new).all{...} }` so even a transient empty can't collapse the header — belt-and-suspenders on top of the transaction fix.

**UI** (`LibraryViewModel.kt`): add `markShelved(ratingKey, shelved)` mirroring `markCompleted` (`:128-132`).
**Build + test:** fresh install migrates cleanly; Continue Listening still populates; swipe-refresh no longer flickers (the transactional refresh means one emission, no empty-collapse).

### Phase 2 — PlaybackManager (the core fix)
New `ui/playback/PlaybackManager.kt` (`@Singleton` via Hilt `@Inject constructor`, deps: `PlexRepository`, `SessionManager`, `@ApplicationContext Context`). Behavior:
- **Persistent connection**: holds one `MediaBrowserCompat` + `MediaControllerCompat` for the app's life. Connect lazily on first `play()` (or on MainActivity start if a session token already exists); reconnect on `onConnectionSuspended`. Survives fragment teardown (no `onStop` disconnect). Expose `state: StateFlow<NowPlayingUiState>`.
- **Position source**: keep the existing `EXTRA_ABSOLUTE_POSITION` extras contract (`PlayerFragment.kt:274-283`, `AudiobookPlaybackService` writes it). Move the 500ms polling loop (`PlayerFragment.kt:302-359`) into `PlaybackManager`; emit position via the `StateFlow`.
- **`play(ratingKey, startPositionMs? = null)`**: resolve server URL + build stream URL **once** (reuse `PlexRepository.resolveAndRefreshServerUrl()` and the stream-URL logic from `PlayerViewModel.buildStreamUrl()` `PlayerViewModel.kt:203-228`), push chapters via the service companion (`AudiobookPlaybackService.pendingChapters`), and start the service with `startForegroundService(...)` instead of `startService(...)`.
- **Play-from-Resume / un-complete-on-replay**: preserve the `loadBook` contract at `PlayerViewModel.kt:55-62` — if `cached.completed && rawPosition near end`, reset start to 0 and `setCompleted(ratingKey, false)` before play. This lives in `PlaybackManager` now.
- **Error reporting**: expose `playbackError` in the state when the connection fails or the service can't be (re)started — so the UI can show a retry, never a silent dead play button.

**Service hardening** (`AudiobookPlaybackService.kt`):
- Wrap the `startForegroundNotification()` calls in `playBook` (`:327`) and `mediaSessionCallback.onPlay` (`:223`) in the same try/catch already used at `:471-489`, so a background-restricted start doesn't crash the service.
- Keep `onStartCommand` `START_NOT_STICKY` (discuss later) but ensure `PlaybackManager` can reissue `startForegroundService` to resurrect a killed service — no app-side "force-quit to restart" path remains.
- Do NOT touch the seek contract (`onSeekTo` chapter-relative) or the STATE_ENDED truncated-file detection (`:509-530`) per the handoff.

**MainActivity** (`MainActivity.kt`):
- Replace `startPlayback(...)` (`:83-103`) with a `PlaybackManager.play(...)` call (or keep an overload that forwards). Use `startForegroundService`.
- The `EXTRA_*` companion contract stays (service still reads intents); the manager owns building the intent.

**Build + test:** play a book from Library → audio starts; leave the screen and come back via MainActivity → state is intact (no re-probe, position correct); simulate service kill → tapping play/re-tapping Now Playing resurrects it; background-restricted start logs instead of crashing.

### Phase 3 — Mini-player + Bottom nav + Player sheet
**`activity_main.xml`** restructure:
```
[ FragmentContainerView (nav_host, layout_weight 1, constrained above mini-player, below a 0dp barrier) ]
[ MiniPlayerView (id=miniPlayer, height wrap, gone by default) ]          ← driven by PlaybackManager.state (visible iff book != null)
[ BottomNavigationView (id=bottomNav, height wrap) ]
```

**Bottom nav** (`res/menu/bottom_nav.xml`): 4 items — `tab_library` (icon + label "Library"), `tab_now_playing` (icon + label "Now Playing"), `tab_downloads` (↓ icon, label "Downloads" hidden via `labelVisibilityMode=UNLABELED`), `tab_settings` (⚙ icon, unlabeled). Wire `setupWithNavController` with **multi-back-stack** (`saveState`/`restoreState`) so each tab keeps its own stack.

**Nav graph** (`nav_graph.xml`) restructure:
- Remove `libraryFragment`'s `action_library_to_player` and `action_library_to_downloads`/`_settings`; remove `downloadsFragment`'s `action_downloads_to_player` and `detailFragment`'s `action_detail_to_player` (the player becomes a sheet, not a destination). Keep `action_library_to_detail` and `action_detail_to_download`-style nav for detail.
- Start-destination logic stays in `MainActivity` (`:39-42`) but add the **resume-onboarding** branch: if `session.isLoggedIn && serverUrl == null` → start at `serverSetupFragment` (reuse orphaned token) instead of forcing re-auth.
- Player is no longer a nav destination → no entry-point back-stack asymmetry; back from any tab is just that tab's stack.

**`PlayerSheetFragment`** (new, `ui/player/PlayerSheetFragment.kt`) or a `BottomSheetDialogFragment` shown from `MainActivity`:
- Reuses existing `fragment_player.xml` content (cover, title/author/chapter, seekbar, skip/play/skip, speed, chapters list — all already present).
- Binds to `PlaybackManager.state` instead of a `PlayerViewModel`. Controls call `PlaybackManager.pause()/resume()/seekTo()/skipChapter()/setSpeed()`.
- Dismissed by swipe-down or system back; never pushed on the back stack. The mini-player re-shows on dismiss.
- **Now Playing tab** expands the same sheet.

**Mini-player** (`MiniPlayerView`): cover (small), title/author, play/pause, expand-on-tap. Collects `PlaybackManager.state`; play/pause → `PlaybackManager.pause()/resume()`.

**Back-press** (`MainActivity`): retire the manual `OnBackPressedCallback` double-tap hack (`:46-73`); replace with: if the player sheet is showing, back dismisses it; else let NavController + bottom-nav handle standard back. Keep one `OnBackPressedCallback` only to dismiss the sheet and to back-to-exit on the root tab. Fix the lifecycle leak: cancel the ` paraDelayed` reset in `onDestroy`.

**Build + test:** 4-tab nav works with state restoration; mini-player appears on play and re-expands the sheet; back/swipe-down dismisses sheet; toggling tabs never loses stacks; bottom nav stays visible across Detail/Downloads/Settings.

### Phase 4 — Shelf UX + onboarding/auth fixes
**Continue Listening long-press** (`ContinueListeningAdapter.kt`, `HeaderAdapter.kt:22-25, ContinueListeningHeaderAdapter`): add `onBookLongClick` → show an `AlertDialog` with two actions:
- **"Put this book back on the shelf"** → `viewModel.markShelved(ratingKey, true)` (removes from Continue Listening, resume preserved, stays in browse grid).
- **"Mark as Read"** → existing `viewModel.markCompleted(ratingKey, true)` path.
Wire long-press through the carousel item (`item_continue_listening.xml` root `setOnLongClickListener`) and the vertical list path in `ContinueListeningHeaderAdapter.bind` (`:88-114`).
**Re-admit on play**: in `PlaybackManager.play()`, if the book is `shelved`, call `setShelved(ratingKey, false)` so it returns to Continue Listening when the user resumes. (Mirrors the un-complete-on-replay logic.)

**Auth/onboarding fixes:**
- `MainActivity` start-destination: add `authToken != null && serverUrl == null` → `serverSetupFragment` branch (Phase 3 wiring; finalize here).
- `ServerSetupFragment`: make PIN dialog cancelable by back (`setCancelable(true)` — `ServerSetupFragment.kt:153`); on `SetupStep.Error` ("no servers/libraries"), offer "Reuse existing sign-in" / "Sign out" so an orphaned token isn't a dead end.
- Clean half-authed token: if the user backs out of serverSetup to authOffer a state where the token is cleared or reused (decision: on blowing out of setup, clear `authToken` so a clean re-auth; do NOT leave the orphaned token forcing re-auth next launch silently).
- **Sign-out stops playback** (`SettingsFragment.kt:132-142`): before navigating to auth, call `PlaybackManager.stop()` (or `stopService` + release controller) — fix the "audio keeps playing after sign-out" bug.
- **Re-onboarding back stack**: since Settings→ServerSetup→Library now goes through the new bottom-nav/tab world and ServerSetup isn't a tab, the orphaned-Settings-stack bug (`[lib→settings→lib]`) collapses — verify; add `popUpTo` if any route still stacks it.
- **Stale comment** cleanup at `AuthFragment.kt:94` ("Skip server setup").

**Build + test:** Start a book, get 3 chapters in, long-press Continue Listening → "Put back on shelf" → it leaves the carousel, stays in browse, tap play later resumes from 3-chapter mark and reappears in Continue Listening. Sign out mid-playback stops audio. Backed out of server setup doesn't strand a dead token.

### Phase 5 — Cleanup & polish
- Remove dead code: `MainActivity.sendChaptersToService` if unused, `updateDownloadBadge` stub (`MainActivity.kt:105`) once a real downloads badge is wired (optional), commented-out blocks in `PlayerViewModel`/`PlayerFragment` once the new flow is in.
- `PlayerViewModel` becomes thin/removed; its `buildStreamUrl` logic moved into `PlaybackManager`.
- Ensure `collect` (not `collectLatest`) is used for `continueListening`/`completedBooks`/`downloadedKeys` (handoff preference) — check `LibraryFragment.observeData` (`:205-236`).
- Strings: add `@string` for "Put this book back on the shelf", "Mark as Read", "Now Playing", "Library", "Downloads", "Settings" if missing.
- Final build + full manual test pass.

---

## Critical files

| File | Changes |
|---|---|
| `data/local/Entities.kt:30-45` | add `shelved: Boolean = false` |
| `data/local/AudiobookDatabase.kt` | version 3, `MIGRATION_2_3` |
| `data/local/Daos.kt:96-109,62-66,117-129` | `shelved` in continue-listening WHERE + projection; `setShelved`/`getShelvedKeys`; transactional refresh method |
| `data/PlexRepository.kt:277-312,317-319` | transactional `fetchLibrary` preserving completed**+shelved**; `setShelved` wrapper |
| `ui/playback/PlaybackManager.kt` (new) | persistent `MediaController`, `StateFlow<NowPlayingUiState>`, `play()`, polling, error surfacing |
| `service/AudiobookPlaybackService.kt:181,223,327` | foreground try/catch on `onPlay`/`playBook`; keep seek/STATE_ENDED untouched |
| `res/navigation/nav_graph.xml` | drop player as destination; player = sheet; Downloads/Settings as tab roots |
| `ui/MainActivity.kt:39-43,46-73,83-103` | resume-onboarding start branch; retire manual back hack; `PlaybackManager.play`; fix `onDestroy` leak |
| `res/layout/activity_main.xml` | add `MiniPlayerView` + `BottomNavigationView` |
| `res/menu/bottom_nav.xml` (new) | 4-tab menu |
| `ui/player/PlayerSheetFragment.kt` (new) + reuse `fragment_player.xml` | sheet UI bound to `PlaybackManager` |
| `ui/library/LibraryViewModel.kt:128-132` | `markShelved` |
| `ui/library/ContinueListeningAdapter.kt`, `HeaderAdapter.kt:22-25` | long-press → shelf/complete dialog |
| `ui/library/LibraryFragment.kt:62-66` | Continue Listening click still → player sheet via `PlaybackManager.play` (stays direct-to-player per user) |
| `ui/settings/SettingsFragment.kt:132-142` | sign-out stops playback |
| `ui/auth/ServerSetupFragment.kt:138-155`, `AuthFragment.kt:94` | cancelable PIN; error affordance; stale comment |

**Patterns reused (no new code invented where existing works):**
- Seek contract / `EXTRA_ABSOLUTE_POSITION` — reused from `PlayerFragment.kt:274-298` and the service.
- `loadBook` replay/un-complete logic — moved from `PlayerViewModel.kt:55-62` into `PlaybackManager.play`.
- `buildStreamUrl` local-vs-stream routing — moved from `PlayerViewModel.kt:203-228` into `PlaybackManager`.
- `ContinueListeningHeaderAdapter` empty-collapse logic (`HeaderAdapter.kt:56-66`) stays; made unreachable-after-empty by the transactional refresh.

---

## Verification (end-to-end)

After each phase, build (`./gradlew :app:assembleDebug`) and install on a device/emulator with a reachable Plex server. `adb logcat` per the handoff (Windows `cmd.exe`, not PowerShell).

**Final manual matrix:**
1. Cold launch logged-in → goes straight to Library (or serverSetup if token-but-no-server).
2. Swipe-refresh on Library → **no flicker**; Continue Listening stable.
3. Tap a book → Detail → Play → audio; back → Library; mini-player visible; tap mini-player → sheet re-expands with correct position/cover/chapter/remaining.
4. Kill the app from recents while playing → relaunch → return to Now Playing → position intact → play resumes (no force-quit needed).
5. Background the app during playback (phone call sim) → logs instead of `ForegroundServiceStartNotAllowedException`; resumes on focus regain.
6. Play a book 3 chapters in → long-press Continue Listening → "Put back on shelf" → leaves carousel, still in browse grid → play again later → resumes from 3-chapter mark → reappears in Continue Listening.
7. Long-press → "Mark as Read" → moves to Completed section.
8. Bottom nav: Library | Now Playing | Downloads(↓) | Settings(⚙) — each preserves its own stack; Downloads/Settings reachable from anywhere.
9. Back from sheet dismisses it; back on root tab exits (single-handler, no leak).
10. Sign out while playing → audio stops → lands on Auth with clean stack.
11. Complete a book (auto-complete) → STATE_ENDED real-end path still marks complete + resets position (unchanged seek/end-of-book contract).

---

## Notes / open items deferred
- Media3 migration (user may revisit chapter-relative notification progress feasibility).
- `fallbackToDestructiveMigration()` risk to `playback_progress` on missed migration — flagged, left for a hardening pass after initial re-arch stabilizes.
- Downloads badge on the bottom-nav Downloads tab (the `updateDownloadBadge` stub) — wire if time allows in Phase 5.
- Known issue #1 in the handoff (completed-book replay `ForegroundServiceStartNotAllowedException`) is likely fixed by the foreground try/catch in Phase 2; confirm via the Phase-2 logcat capture.
