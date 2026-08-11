# Plex Audiobooks — Android App

A native Android audiobook player for your Plex server. Built with Kotlin, ExoPlayer/Media3, Room, Hilt, and Retrofit.

---

## Features

- **Plex OAuth sign-in** — secure PIN-based login via plex.tv, no password ever stored
- **Encrypted token storage** — auth token stored in Android EncryptedSharedPreferences (AES-256)
- **Full library browsing** — grid/list view of all audiobooks with cover art, sortable by title / author / duration / date added
- **Continue Listening** — a section showing books you've started (horizontal carousel in grid mode, vertical list in list mode); long-press a book to **"Put this back on the shelf"** (hide it from Continue Listening without marking it read — resume position preserved, book stays visible in your library) or **"Mark as Read"**
- **Completed section** — books you've finished collect at the bottom; long-press to mark a book Unread
- **Mini-player + bottom navigation** — a mini-player bar sits above the bottom nav; tap to expand the full player. A 3-tab bottom nav (Library | Downloads | Settings) keeps each tab's own back-stack
- **Progress sync** — position saved locally every 10 seconds AND reported to Plex (`/:/timeline`) so other clients stay in sync; on a fresh install, resuming a book with server-saved progress prompts to **Resume from** the saved position or **Start from beginning**, then resumes silently on later sessions
- **Offline read-ahead cache** — a sliding window of audio ahead of your playback position, sized by **download hours** (default 4h; configurable 1–8h in Settings). Extends automatically as you listen, and uses HTTP `Range` requests to fetch only the missing bytes. Jobs queue while offline and fire on reconnect
- **Full-book download** — download an entire book for offline listening from the Book Detail screen
- **Local-first playback** — plays from the local file when your position is within the cached window, falls back to streaming when beyond it
- **Truncated-file detection** — if a partial local file ends before the book does, the player seamlessly switches to streaming rather than treating it as the end of the book
- **Chapter support** — tap any chapter to jump; current chapter highlighted during playback
- **Variable playback speed** — 0.5×, 0.75×, 1.0×, 1.25×, 1.5×, 1.75×, 2.0×
- **Skip forward/back** — configurable (default: 30s forward, 15s back)
- **Lock screen & notification controls** — chapter-relative progress and chapter title via Android `MediaSessionCompat`
- **System theme** — follows Android light/dark mode automatically
- **Cover art** — loaded from your Plex server with Glide
- **Remote access** — parallel probing of all known server URLs (LAN + remote + relay) with automatic fallback, so the app isn't stuck on a home IP when you're away

---

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 35 (compile/target), API 26+ (min)
- Physical device or emulator running Android 8.0+ (API 26+)
- A running Plex Media Server with an audiobook/music library

---

## Setup

### 1. Clone / open the project

Open the `PlexAudiobooks` folder in Android Studio. Gradle will sync automatically.

> **Build note:** there is no Gradle wrapper in the repo, so build from Android Studio (`assembleDebug` and click **Run ▶**), not from the command line.

### 2. Build & run

Connect an Android device or start an emulator, then click **Run ▶**.

No API keys or secrets are required — the app uses Plex's standard OAuth PIN flow.

### 3. First launch

1. Tap **Sign in with Plex** — your browser opens plex.tv
2. Approve the app on plex.tv, then return to the app
3. Pick a home user (if your Plex account has PIN-protected users) and enter the PIN
4. The app discovers your servers and libraries automatically; if no server is found you can enter the URL manually

If you've signed in before but haven't picked a server, the app returns you to server setup rather than making you sign in again.

---

## Project Structure

```
app/src/main/java/com/plexaudiobooks/
├── PlexAudiobooksApp.kt          # Hilt application, WorkManager Configuration.Provider
├── api/
│   └── PlexApi.kt                # Retrofit interfaces (PlexTvApi, PlexServerApi, PlexResourcesApi)
├── data/
│   ├── model/PlexModels.kt       # All data models
│   ├── local/
│   │   ├── AudiobookDatabase.kt  # Room database (v3)
│   │   ├── Daos.kt               # DAOs for progress, downloads, library cache
│   │   └── Entities.kt           # Room entities (incl. completed / shelved flags)
│   └── PlexRepository.kt         # Single source of truth
├── di/
│   └── AppModule.kt              # Hilt modules
├── service/
│   ├── AudiobookPlaybackService.kt  # ExoPlayer + MediaSessionCompat foreground service
│   └── BookDownloadWorker.kt        # WorkManager read-ahead / download job
├── ui/
│   ├── MainActivity.kt           # Hosts nav graph, mini-player, player sheet
│   ├── auth/
│   │   ├── AuthFragment.kt        # OAuth sign-in screen
│   │   └── ServerSetupFragment.kt
│   ├── library/
│   │   ├── LibraryFragment.kt     # Library grid/list + Continue Listening + Completed (ConcatAdapter)
│   │   ├── LibraryViewModel.kt
│   │   └── adapters...
│   ├── detail/
│   │   ├── DetailFragment.kt      # Book detail, download button
│   │   └── DetailViewModel.kt
│   ├── playback/
│   │   ├── PlaybackManager.kt    # @Singleton owner of playback state (one persistent MediaController)
│   │   └── NowPlayingUiState.kt  # Now-Playing state consumed by mini-player + sheet
│   ├── player/
│   │   ├── PlayerSheetFragment.kt # Bottom-sheet full player (overlay, not a nav destination)
│   │   └── ChapterAdapter.kt
│   ├── downloads/
│   │   └── DownloadsFragment.kt
│   └── settings/
│       └── SettingsFragment.kt
└── util/
    └── SessionManager.kt         # Encrypted prefs, URL builders, server resolution
```

---

## Architecture

```
UI Layer (Fragments + ViewModels)  ──collect──►  PlaybackManager (singleton @Singleton)
        │                                                  │
        ▼                                                  ▼
Repository (PlexRepository) ─────► Room DB     MediaControllerCompat ─► AudiobookPlaybackService
        │                         (local cache,    (persistent for process lifetime)
        ▼                          progress,                 │
   Plex API (Retrofit)            downloads,                ▼
        │                          completed/shelved)   ExoPlayer
   SessionManager ◄─────────────────────────             (streams local file or server URL)
   (EncryptedSharedPreferences; server URL resolution, tokens, download hours)
```

**Key design decisions:**

- **Singleton PlaybackManager (v1.6.0)**: one persistent `MediaControllerCompat` bound to the playback service for the whole process — it is **not** torn down on fragment lifecycle, which fixes the old "playback dies, can't restart without force-quitting" symptom. Every Now-Playing UI (mini-player, player sheet) collects `PlaybackManager.state` instead of re-deriving book/position on each screen.
- **Player is a sheet, not a screen**: the full player is a `BottomSheetDialogFragment` shown from `MainActivity`; swipe-down or back dismisses it. It is not a navigation destination.
- **Service stays `MediaSessionCompat` (not Media3's `MediaSessionService`)**: that API cannot override `PlaybackState.position`, which would make chapter-relative notification/car-display progress impossible. The seek contract (chapter-relative into `onSeekTo`) is preserved.
- **Single source of truth**: Library data flows `API → Room → UI` via `Flow`. The UI always reads from Room; the repository refreshes from the network and re-applies user-set completed/shelved flags atomically across a refresh (so the Continue Listening section never flickers and disappears on swipe-refresh).
- **Progress persistence**: Playback position is saved to Room every 10 seconds. On app kill/crash, progress is recovered from Room on next launch and re-synced to Plex.
- **Offline routing**: `PlaybackManager.buildStreamUrl()` returns a `file://` URI when the resume position is within the downloaded window; otherwise it streams from the server so ExoPlayer never seeks past the end of a truncated local file.
- **Secure auth**: Plex tokens are never stored in plaintext. `EncryptedSharedPreferences` uses AES-256-GCM for values and AES-256-SIV for keys, backed by Android Keystore.

---

## Customising skip intervals

In **Settings**, tap "Skip forward" or "Skip back" to choose from preset values (5s–60s). Changes take effect immediately for the next skip action.

---

## Offline download details

- Downloads are managed by **WorkManager** (`BookDownloadWorker`)
- **Read-ahead**: as you listen, the service automatically extends the cache window to `current position + download hours` (default 4h). For single-file (M4B) books it uses an HTTP `Range` request to append only the missing bytes; for multi-file books it downloads from byte 0 up to the window.
- **Full-book download**: the Download button on Book Detail downloads the whole book regardless of the hours setting.
- One book can be downloaded at a time; downloading a new book does **not** auto-delete the previous one — you must manually delete it from the Downloads screen.
- Download progress is shown live on the Book Detail screen via `WorkInfo`.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "No music/audiobook library found" | Make sure your Plex library type is **Music** or **Artist**. Plex treats audiobooks as music albums. |
| Cannot connect to local server | Check the URL includes the port (`http://192.168.x.x:32400`). The app allows HTTP on local IPs via `network_security_config.xml`. The app also probes all known server URLs (LAN + remote + relay) in parallel and falls back automatically. |
| Cover art not loading | Verify the server URL is correct. Cover art uses the same base URL with your auth token appended. |
| Progress not syncing to other Plex clients | Plex progress sync (`/:/timeline`) requires the track `ratingKey` and `key` — these are fetched from the server metadata when you start a book. |
| Tapping Play shows the mini-player but plays no audio | Make sure the book's server is reachable (the app builds the stream URL at play time). If you're offline, only downloaded books will play. |

---

## Adding Safe Args (required for navigation)

Add to your project-level `build.gradle` plugins block:
```groovy
id 'androidx.navigation.safeargs.kotlin' version '2.7.6' apply false
```
And in `app/build.gradle`:
```groovy
id 'androidx.navigation.safeargs.kotlin'
```

---

## License

MIT — free to use, modify, and distribute.
