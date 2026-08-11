package com.plexaudiobooks.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.plexaudiobooks.ui.playback.ResumePrompt
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
    private var resumeDialog: AlertDialog? = null
    private var shownResumePrompt: ResumePrompt? = null

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

        // Bottom nav: the three tab ids match nav-graph destinations, so
        // setupWithNavController binds them with multi-back-stack (save/restore state).
        // The full player is reached via the mini-player bar, not a tab.
        binding.bottomNav.setupWithNavController(navController)

        // Mini-player lives above the bottom nav; visible only while something is loaded.
        bindMiniPlayer()

        // Resume / start-over prompt: shown by PlaybackManager when a fresh-install book
        // has server-saved progress but no local Room row. The user picks resume or start
        // from the beginning; dismissing the dialog aborts the play without audio.
        observeResumePrompt()

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
                    // While play() is resolving the book, show a loading affordance instead
                    // of a blank bar so the tap is acknowledged within a frame and a second
                    // tap on the play button is ignored (disabled).
                    if (state.isStarting && state.book == null) {
                        binding.miniPlayer.tvMiniTitle.text =
                            getString(R.string.loading)
                        binding.miniPlayer.tvMiniAuthor.text = ""
                        binding.miniPlayer.btnMiniPlayPause.isEnabled = false
                        binding.miniPlayer.btnMiniPlayPause.setIconResource(R.drawable.ic_play)
                    } else {
                        binding.miniPlayer.tvMiniTitle.text = state.book?.title ?: ""
                        binding.miniPlayer.tvMiniAuthor.text = state.book?.author ?: ""
                        binding.miniPlayer.btnMiniPlayPause.isEnabled = true
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
    }

    fun showPlayerSheet() {
        if (playbackManager.state.value.hasContent) {
            if (playerSheet == null) playerSheet = PlayerSheetFragment()
            if (!playerSheet!!.isAdded) {
                playerSheet!!.show(supportFragmentManager, "playerSheet")
            }
        }
    }

    private fun observeResumePrompt() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playbackManager.state.collect { state ->
                    val prompt = state.showResumePrompt
                    if (prompt == null) {
                        // Prompt cleared (answered or aborted). Dismiss any dialog we
                        // were showing so it doesn't linger across recomposition.
                        resumeDialog?.let { if (it.isShowing) it.dismiss(); resumeDialog = null }
                        shownResumePrompt = null
                        return@collect
                    }
                    // A NEW prompt (different book or position) supersedes the one we're
                    // currently showing: dismiss the stale dialog and show the new one.
                    if (prompt != shownResumePrompt) {
                        resumeDialog?.let { if (it.isShowing) it.dismiss(); resumeDialog = null }
                        showResumeDialog(prompt)
                        shownResumePrompt = prompt
                    }
                }
            }
        }
    }

    private fun showResumeDialog(prompt: ResumePrompt) {
        resumeDialog = AlertDialog.Builder(this)
            .setTitle(R.string.resume_title)
            .setMessage(getString(R.string.resume_from, prompt.formattedPosition))
            .setPositiveButton(R.string.resume_button) { dlg, _ ->
                // Primary action — resume from the server-saved position.
                playbackManager.confirmResume(true)
                dlg.dismiss()
            }
            .setNegativeButton(R.string.start_from_beginning) { dlg, _ ->
                playbackManager.confirmResume(false)
                dlg.dismiss()
            }
            .setOnCancelListener {
                // Back press / outside tap / dismissed: abort this play entirely.
                playbackManager.cancelResumePrompt()
            }
            .setCancelable(true)
            .show()
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
        resumeDialog?.let { if (it.isShowing) it.dismiss() }
        playbackManager.release()
    }

    companion object {
        const val ACTION_PLAY = "com.plexaudiobooks.ACTION_PLAY"
        const val EXTRA_RATING_KEY = "rating_key"
        // The ratingKey to use for /:/timeline progress reporting. This is the TRACK
        // ratingKey (not the album) when the book has track children — Plex's timeline
        // endpoint keys off the track, so reporting with the album key or a part key (e.g.
        // /library/parts/12345/…) is silently dropped. Falls back to the album ratingKey
        // for books without track children. See PlaybackManager.play() → sendPlayIntent.
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
