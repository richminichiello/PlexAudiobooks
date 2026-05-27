# Plex Audiobooks — Android App

A native Android audiobook player for your Plex server. Built with Kotlin, ExoPlayer/Media3, Room, Hilt, and Retrofit.

---

## Features

- **Plex OAuth sign-in** — secure PIN-based login via plex.tv, no password ever stored
- **Encrypted token storage** — auth token stored in Android EncryptedSharedPreferences (AES-256)
- **Full library browsing** — grid view of all audiobooks with cover art
- **Progress sync** — position saved locally every 10 seconds AND reported to Plex so other clients stay in sync
- **Offline support** — download ~3–4 hours of any single book (capped at 110 MB) for offline listening
- **Chapter support** — tap any chapter to jump; current chapter highlighted during playback
- **Variable playback speed** — 0.5×, 0.75×, 1.0×, 1.25×, 1.5×, 1.75×, 2.0×
- **Skip forward/back** — configurable (default: 30s forward, 15s back)
- **Lock screen & notification controls** — via Android MediaSession / Media3
- **System theme** — follows Android light/dark mode automatically
- **Cover art** — loaded from your Plex server with Glide

---

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Physical device or emulator running Android 8.0+ (API 26+)
- A running Plex Media Server with an audiobook/music library

---

## Setup

### 1. Clone / open the project

Open the `PlexAudiobooks` folder in Android Studio. Gradle will sync automatically.

### 2. Build & run

Connect an Android device or start an emulator, then click **Run ▶**.

No API keys or secrets are required — the app uses Plex's standard OAuth PIN flow.

### 3. First launch

1. Tap **Sign in with Plex** — your browser opens plex.tv
2. Approve the app on plex.tv, then return to the app
3. Enter your Plex server URL (e.g. `http://192.168.1.50:32400`)
4. The app discovers your audiobook library automatically

---

## Project Structure

```
app/src/main/java/com/plexaudiobooks/
├── PlexAudiobooksApp.kt          # Hilt application
├── api/
│   └── PlexApi.kt                # Retrofit interfaces (PlexTvApi, PlexServerApi)
├── data/
│   ├── model/PlexModels.kt       # All data models
│   ├── local/
│   │   ├── AudiobookDatabase.kt  # Room database
│   │   ├── Daos.kt               # DAOs for progress, downloads, library cache
│   │   └── Entities.kt           # Room entities
│   └── PlexRepository.kt         # Single source of truth
├── di/
│   └── AppModule.kt              # Hilt modules
├── service/
│   ├── AudiobookPlaybackService.kt  # ExoPlayer + MediaSession foreground service
│   └── BookDownloadWorker.kt        # WorkManager download job
├── ui/
│   ├── MainActivity.kt
│   ├── auth/
│   │   ├── AuthFragment.kt       # OAuth sign-in screen
│   │   ├── AuthViewModel.kt      # PIN polling logic
│   │   └── ServerSetupFragment.kt
│   ├── library/
│   │   ├── LibraryFragment.kt    # Book grid + "continue listening" card
│   │   ├── LibraryViewModel.kt
│   │   └── BookAdapter.kt
│   ├── detail/
│   │   ├── DetailFragment.kt     # Book detail, download button
│   │   └── DetailViewModel.kt
│   ├── player/
│   │   ├── PlayerFragment.kt     # Full player UI
│   │   ├── PlayerViewModel.kt
│   │   └── ChapterAdapter.kt
│   ├── downloads/
│   │   └── DownloadsViewModelAndAdapter.kt
│   └── settings/
│       └── SettingsFragment.kt
└── util/
    └── SessionManager.kt         # Encrypted prefs, URL builders
```

---

## Architecture

```
UI Layer (Fragments + ViewModels)
        │
        ▼
Repository (PlexRepository)
        │
   ┌────┴────┐
   │         │
Plex API   Room DB
(Retrofit) (local cache,
            progress,
            downloads)
        │
   SessionManager
   (EncryptedSharedPreferences)
```

**Key design decisions:**

- **Single source of truth**: Library data flows `API → Room → UI` via `Flow`. The UI always reads from Room; the repository refreshes from the network.
- **Progress persistence**: Playback position is saved to Room every 10 seconds. On app kill/crash, progress is recovered from Room on next launch.
- **Offline detection**: `PlayerViewModel` checks for a `DownloadedBookEntity` first. If found, it builds a `file://` URI for ExoPlayer instead of a network URL.
- **Secure auth**: Plex tokens are never stored in plaintext. `EncryptedSharedPreferences` uses AES-256-GCM for values and AES-256-SIV for keys, backed by Android Keystore.

---

## Customising skip intervals

In **Settings**, tap "Skip forward" or "Skip back" to choose from preset values (5s–60s). Changes take effect immediately for the next skip action.

---

## Offline download details

- Downloads are managed by **WorkManager** (`BookDownloadWorker`)
- Max download: `110 MB` (~3.5 hours at 64 kbps)
- Uses HTTP `Range` header to fetch only the first N bytes
- One book can be downloaded at a time; downloading a new book does **not** auto-delete the previous one — you must manually delete from the Downloads screen
- Download progress is shown live on the Book Detail screen via `WorkInfo`

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "No music/audiobook library found" | Make sure your Plex library type is **Music** or **Artist**. Plex treats audiobooks as music albums. |
| Cannot connect to local server | Check the URL includes the port (`http://192.168.x.x:32400`). The app allows HTTP on local IPs via `network_security_config.xml`. |
| Cover art not loading | Verify the server URL is correct. Cover art uses the same base URL with your auth token appended. |
| Progress not syncing to other Plex clients | Plex progress sync requires the `ratingKey` and `key` fields — these are fetched from the server metadata on each play. |

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
