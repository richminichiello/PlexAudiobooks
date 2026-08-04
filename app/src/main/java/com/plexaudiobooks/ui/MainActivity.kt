package com.plexaudiobooks.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.databinding.ActivityMainBinding
import com.plexaudiobooks.service.AudiobookPlaybackService
import com.plexaudiobooks.ui.playback.PlaybackManager
import com.plexaudiobooks.ui.player.PlayerSheetFragment
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject lateinit var session: SessionManager
    @Inject lateinit var playbackManager: PlaybackManager

    // Back-to-exit: track whether user pressed back once already
    private var backPressedOnce = false
    private var playerSheet: PlayerSheetFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        val graph = navController.navInflater.inflate(R.navigation.nav_graph)
        val startDest = when {
            // Fully authed + have a server → straight to the library.
            session.isLoggedIn && session.serverUrl != null -> R.id.libraryFragment
            // Token exists but no server yet (half-authed / backed out of server setup):
            // resume onboarding at server selection instead of forcing a fresh OAuth.
            session.isLoggedIn -> R.id.serverSetupFragment
            else -> R.id.authFragment
        }
        graph.setStartDestination(startDest)
        navController.graph = graph

        // Keep the persistent media controller alive for the life of the activity.
        playbackManager.ensureConnected()

        // Bottom nav: all four tab ids match nav-graph destinations, so
        // setupWithNavController binds them with multi-back-stack (save/restore state).
        binding.bottomNav.setupWithNavController(navController)

        // Mini-player lives above the bottom nav; visible only while something is loaded.
        bindMiniPlayer()

        // Back-press: if the player sheet is open, dismiss it first; otherwise hand off to
        // NavController, and on a true root screen warn-then-exit (stopping playback).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val sheet = playerSheet
                if (sheet != null && sheet.isVisible) {
                    sheet.dismiss(); return
                }
                val currentDest = navController.currentDestination?.id
                val isRootScreen = currentDest == R.id.libraryFragment ||
                                   currentDest == R.id.authFragment
                if (isRootScreen) {
                    if (backPressedOnce) {
                        stopPlaybackAndExit()
                    } else {
                        backPressedOnce = true
                        Toast.makeText(this@MainActivity,
                            "Press back again to exit", Toast.LENGTH_SHORT).show()
                        binding.root.postDelayed({ backPressedOnce = false }, 2000)
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    // ── Mini-player ─────────────────────────────────────────────────────────────

    private fun bindMiniPlayer() {
        // binding.miniPlayer is a ViewMiniPlayerBinding (the <include>); .root is the
        // LinearLayout mini-player root, which is what we physically click.
        val mini = binding.miniPlayer.root
        mini.setOnClickListener { showPlayerSheet() }
        binding.miniPlayer.btnMiniPlayPause.setOnClickListener {
            playbackManager.togglePlayPause()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playbackManager.state.collect { state ->
                    mini.isVisible = state.hasContent
                    if (!state.hasContent) return@collect
                    binding.miniPlayer.tvMiniTitle.text = state.book?.title ?: ""
                    binding.miniPlayer.tvMiniAuthor.text = state.book?.author ?: ""
                    binding.miniPlayer.btnMiniPlayPause.setIconResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    val thumbUrl = playbackManager.session.buildThumbUrl(state.book?.thumbPath)
                    if (thumbUrl != null) {
                        Glide.with(this@MainActivity).load(thumbUrl)
                            .placeholder(R.drawable.ic_book_placeholder)
                            .into(binding.miniPlayer.ivMiniCover)
                    }
                }
            }
        }
    }

    fun showPlayerSheet() {
        if (playbackManager.state.value.hasContent) {
            if (playerSheet == null) playerSheet = PlayerSheetFragment()
            if (!playerSheet!!.isAdded) {
                playerSheet!!.show(supportFragmentManager, "playerSheet")
            }
        }
    }

    private fun stopPlaybackAndExit() {
        // Stop the playback service so audio doesn't continue after exit
        playbackManager.stop()
        val serviceIntent = Intent(this, AudiobookPlaybackService::class.java)
        stopService(serviceIntent)
        finishAffinity()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.root.removeCallbacks { backPressedOnce = false } // avoid leak from postDelayed
        playbackManager.release()
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
