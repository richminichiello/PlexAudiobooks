package com.plexaudiobooks.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.plexaudiobooks.R
import com.plexaudiobooks.databinding.ActivityMainBinding
import com.plexaudiobooks.service.AudiobookPlaybackService
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject lateinit var session: SessionManager

    // Back-to-exit: track whether user pressed back once already
    private var backPressedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        val graph = navController.navInflater.inflate(R.navigation.nav_graph)
        graph.setStartDestination(
            if (session.isLoggedIn && session.serverUrl != null) R.id.libraryFragment
            else R.id.authFragment
        )
        navController.graph = graph

        // Back-press handler: on the root screen (library), warn once then exit + stop service
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentDest = navController.currentDestination?.id
                val isRootScreen = currentDest == R.id.libraryFragment ||
                                   currentDest == R.id.authFragment

                if (isRootScreen) {
                    if (backPressedOnce) {
                        // Stop the playback service completely and exit
                        stopPlaybackAndExit()
                    } else {
                        backPressedOnce = true
                        Toast.makeText(
                            this@MainActivity,
                            "Press back again to exit",
                            Toast.LENGTH_SHORT
                        ).show()
                        // Reset after 2 seconds
                        binding.root.postDelayed({ backPressedOnce = false }, 2000)
                    }
                } else {
                    // Not on root screen — normal back navigation
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun stopPlaybackAndExit() {
        // Stop the playback service so audio doesn't continue after exit
        val serviceIntent = Intent(this, AudiobookPlaybackService::class.java)
        stopService(serviceIntent)
        finishAffinity()
    }

    fun startPlayback(
        ratingKey: String, key: String, streamUrl: String,
        title: String, author: String?, thumbUrl: String?,
        startPositionMs: Long, speed: Float,
        partKeys: List<String> = emptyList(), durationMs: Long = 0L
    ) {
        val serviceIntent = Intent(this, AudiobookPlaybackService::class.java).apply {
            action = ACTION_PLAY
            putExtra(EXTRA_RATING_KEY, ratingKey)
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_STREAM_URL, streamUrl)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_AUTHOR, author)
            putExtra(EXTRA_THUMB_URL, thumbUrl)
            putExtra(EXTRA_START_POSITION, startPositionMs)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_PART_KEYS, partKeys.joinToString(","))
            putExtra(EXTRA_DURATION_MS, durationMs)
        }
        startService(serviceIntent)
    }

    fun updateDownloadBadge(count: Int) { /* future: bottom nav badge */ }

    fun sendChaptersToService(chapters: List<com.plexaudiobooks.data.model.Chapter>) {
        // Always update the companion so service picks up on next play
        com.plexaudiobooks.service.AudiobookPlaybackService.pendingChapters = chapters
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.plexaudiobooks.ACTION_PLAY"
        const val EXTRA_RATING_KEY = "rating_key"
        const val EXTRA_KEY = "key"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_THUMB_URL = "thumb_url"
        const val EXTRA_START_POSITION = "start_position"
        const val EXTRA_SPEED      = "speed"
        const val EXTRA_PART_KEYS  = "part_keys"
        const val EXTRA_DURATION_MS      = "duration_ms"
    }
}
