package com.plexaudiobooks.ui.player

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.data.model.Chapter
import com.plexaudiobooks.databinding.FragmentPlayerBinding
import com.plexaudiobooks.service.AudiobookPlaybackService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlayerViewModel by viewModels()
    private val args: PlayerFragmentArgs by navArgs()

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    private var positionJob: Job? = null
    private lateinit var chapterAdapter: ChapterAdapter
    private var playCommandSent = false

    // Local copy of chapters — updated when ViewModel loads them.
    // Used directly here so we never depend on a stale ViewModel chapter index.
    private var chapters: List<Chapter> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChapterList()
        setupControls()
        viewModel.loadBook(args.ratingKey)

        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state -> updateUi(state) }
        }
    }

    override fun onStart() {
        super.onStart()
        connectToService()
    }

    override fun onStop() {
        super.onStop()
        positionJob?.cancel()
        mediaController?.unregisterCallback(controllerCallback)
        mediaBrowser?.disconnect()
        mediaBrowser = null
        mediaController = null
    }

    // ── Service connection ────────────────────────────────────────────────────

    private fun connectToService() {
        mediaBrowser = MediaBrowserCompat(
            requireContext(),
            ComponentName(requireContext(), AudiobookPlaybackService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val browser = mediaBrowser ?: return
                    val ctrl = MediaControllerCompat(requireContext(), browser.sessionToken)
                    mediaController = ctrl
                    ctrl.registerCallback(controllerCallback)
                    MediaControllerCompat.setMediaController(requireActivity(), ctrl)
                    // Try to start playback now — book may already be loaded
                    maybeStartPlayback()
                    startPositionPolling()
                }

                override fun onConnectionFailed() {
                    android.util.Log.e("PlayerFragment", "MediaBrowser connection failed")
                }

                override fun onConnectionSuspended() {
                    mediaController?.unregisterCallback(controllerCallback)
                    mediaController = null
                }
            },
            null
        ).also { it.connect() }
    }

    // ── Playback start ────────────────────────────────────────────────────────

    /**
     * Sends the play command to the service exactly once per book navigation.
     * Guards: controller must be connected, book must be loaded, command not yet sent.
     */
    private fun maybeStartPlayback() {
        val ctrl = mediaController ?: return
        val state = viewModel.uiState.value
        if (state.isLoading || state.book == null) return

        // If service is already playing this book, just start polling
        val pbState = ctrl.playbackState?.state
        if (pbState == PlaybackStateCompat.STATE_PLAYING ||
            pbState == PlaybackStateCompat.STATE_BUFFERING) {
            startPositionPolling()
            return
        }

        if (!playCommandSent) {
            playCommandSent = true
            val streamUrl = viewModel.buildStreamUrl(state.book.ratingKey) ?: run {
                playCommandSent = false
                return
            }
            // Pass chapters to service before sending play command
            if (chapters.isNotEmpty()) {
                AudiobookPlaybackService.pendingChapters = chapters
            }
            (requireActivity() as? com.plexaudiobooks.ui.MainActivity)?.startPlayback(
                ratingKey = state.book.ratingKey,
                key = state.book.trackRatingKey ?: state.book.ratingKey,
                streamUrl = streamUrl,
                title = state.book.title,
                author = state.book.author,
                thumbUrl = viewModel.buildThumbUrl(state.book.thumbPath),
                startPositionMs = state.positionMs,
                speed = state.playbackSpeed,
                partKeys = state.book.allPartKeys,
                durationMs = state.book.duration
            )
        }
    }

    // ── MediaController callback ──────────────────────────────────────────────

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            val isPlaying = state?.state == PlaybackStateCompat.STATE_PLAYING
            _binding?.btnPlayPause?.setIconResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            _binding?.btnPlayPause?.isEnabled =
                state?.state != PlaybackStateCompat.STATE_BUFFERING
        }

        override fun onMetadataChanged(metadata: android.support.v4.media.MediaMetadataCompat?) {
            // Chapter title is reflected in the metadata — update the label
            val chapterTitle = metadata?.getString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE
            )
            _binding?.tvCurrentChapter?.text = chapterTitle ?: ""
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun setupChapterList() {
        chapterAdapter = ChapterAdapter { chapter ->
            // Jump directly to this chapter by its index — bypasses position conversion.
            // Service.onSkipToQueueItem receives the index and seeks to chapter.startMs.
            val idx = chapters.indexOf(chapter).toLong()
            if (idx >= 0) mediaController?.transportControls?.skipToQueueItem(idx)
        }
        binding.rvChapters.apply {
            adapter = chapterAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.btnPlayPause.setOnClickListener {
            val ctrl = mediaController ?: return@setOnClickListener
            when (ctrl.playbackState?.state) {
                PlaybackStateCompat.STATE_PLAYING -> ctrl.transportControls.pause()
                else -> ctrl.transportControls.play()
            }
        }

        binding.btnSkipBack.setOnClickListener {
            val absPos = getAbsolutePosition() ?: return@setOnClickListener
            val skipMs = viewModel.session.skipBackSec * 1000L
            sendAbsoluteSeek((absPos - skipMs).coerceAtLeast(0L))
        }

        binding.btnSkipForward.setOnClickListener {
            val absPos = getAbsolutePosition() ?: return@setOnClickListener
            val skipMs = viewModel.session.skipForwardSec * 1000L
            val bookDur = viewModel.uiState.value.durationMs
            sendAbsoluteSeek((absPos + skipMs).coerceAtMost(bookDur))
        }

        binding.btnPrevChapter.setOnClickListener {
            mediaController?.transportControls?.skipToPrevious()
        }

        binding.btnNextChapter.setOnClickListener {
            mediaController?.transportControls?.skipToNext()
        }

        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        binding.btnSpeed.setOnClickListener {
            val current = viewModel.uiState.value.playbackSpeed
            val next = speeds[(speeds.indexOf(current) + 1) % speeds.size]
            viewModel.setSpeed(next)
            mediaController?.transportControls?.setPlaybackSpeed(next)
            binding.btnSpeed.text = "${next}x"
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Show chapter-relative time as user drags
                    val absPos = getAbsolutePosition() ?: return
                    val chapter = chapterAtPosition(absPos)
                    val chapterDur = if (chapter != null) chapter.endMs - chapter.startMs else 1L
                    binding.tvPosition.text = formatMs((progress / 1000f * chapterDur).toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) { positionJob?.cancel() }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Compute absolute seek position from progress within current chapter
                val absPos = getAbsolutePosition() ?: return
                val chapter = chapterAtPosition(absPos)
                val absoluteSeekPos = if (chapter != null) {
                    val chapterDur = chapter.endMs - chapter.startMs
                    chapter.startMs + (seekBar.progress / 1000f * chapterDur).toLong()
                } else {
                    val bookDur = viewModel.uiState.value.durationMs.takeIf { it > 0 } ?: return
                    (seekBar.progress / 1000f * bookDur).toLong()
                }
                // Convert to chapter-relative before sending to service
                sendAbsoluteSeek(absoluteSeekPos)
                startPositionPolling()
            }
        })
    }

    // ── Position helpers ──────────────────────────────────────────────────────

    /**
     * Gets the absolute book position from PlaybackState extras.
     * The service stores the raw ExoPlayer position in EXTRA_ABSOLUTE_POSITION.
     * Falls back to reconstructing from chapter-relative position.
     */
    private fun getAbsolutePosition(): Long? {
        val pbState = mediaController?.playbackState ?: return null
        // Prefer the absolute position stored in extras by the service
        val fromExtras = pbState.extras?.getLong(AudiobookPlaybackService.EXTRA_ABSOLUTE_POSITION, -1L)
        if (fromExtras != null && fromExtras >= 0) return fromExtras
        // Fallback: chapter-relative pos + chapter start (no extras yet, e.g. first frame)
        val chapterRelPos = pbState.position
        val chapter = chapterAtPosition(chapterRelPos) // rough guess without absolute
        return if (chapter != null) chapterRelPos + chapter.startMs else chapterRelPos
    }

    private fun chapterAtPosition(absoluteMs: Long): Chapter? {
        return chapters.lastOrNull { absoluteMs >= it.startMs }
    }

    /**
     * Convert an absolute book position to chapter-relative, then seek.
     * The service's onSeekTo expects chapter-relative positions (matching what
     * the notification bar sends) and adds chapter.startMs to get absolute.
     */
    private fun sendAbsoluteSeek(absoluteMs: Long) {
        val chapter = chapterAtPosition(absoluteMs)
        val chapterRelPos = if (chapter != null) absoluteMs - chapter.startMs else absoluteMs
        mediaController?.transportControls?.seekTo(chapterRelPos)
    }

    // ── Position polling ──────────────────────────────────────────────────────

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = lifecycleScope.launch {
            while (true) {
                val ctrl = mediaController ?: break
                val pbState = ctrl.playbackState

                if (pbState != null) {
                    // Get absolute position from extras — the service stores this so
                    // we never have to reconstruct it from chapter-relative position
                    val absPos = pbState.extras
                        ?.getLong(AudiobookPlaybackService.EXTRA_ABSOLUTE_POSITION, -1L)
                        ?.takeIf { it >= 0 }
                        ?: pbState.position  // fallback: no extras yet

                    val chapter = chapterAtPosition(absPos)
                    val chapterDur = if (chapter != null) chapter.endMs - chapter.startMs else 0L
                    val chapterRelPos = if (chapter != null) absPos - chapter.startMs else absPos

                    // Update ViewModel chapter index for the chapter list highlight
                    val chapterIndex = chapters.indexOfLast { absPos >= it.startMs }
                    if (chapterIndex != viewModel.uiState.value.currentChapterIndex) {
                        viewModel.updateChapterIndex(chapterIndex)
                    }

                    val bookDur = viewModel.uiState.value.durationMs

                    _binding?.let { b ->
                        if (chapterDur > 0) {
                            b.seekBar.progress = ((chapterRelPos.toFloat() / chapterDur) * 1000)
                                .toInt().coerceIn(0, 1000)
                            b.tvPosition.text = formatMs(chapterRelPos)
                            b.tvDuration.text = formatMs(chapterDur)
                        } else {
                            // No chapters — show book-level progress
                            if (bookDur > 0) {
                                b.seekBar.progress = ((absPos.toFloat() / bookDur) * 1000)
                                    .toInt().coerceIn(0, 1000)
                            }
                            b.tvPosition.text = formatMs(absPos)
                            b.tvDuration.text = formatMs(bookDur)
                        }

                        val bookRemaining = bookDur - absPos
                        b.tvTotalRemaining.text = if (bookRemaining > 0)
                            "${formatMs(bookRemaining)} remaining in book" else ""

                        // Chapter label — matches what the notification shows
                        if (chapter != null) {
                            b.tvCurrentChapter.text = chapter.title
                            chapterAdapter.setCurrentChapter(chapterIndex)
                        }
                    }
                }
                delay(500)
            }
        }
    }

    // ── UI update ─────────────────────────────────────────────────────────────

    private fun updateUi(state: PlayerUiState) {
        if (state.isLoading) {
            binding.loadingGroup.visibility = View.VISIBLE
            binding.contentGroup.visibility = View.GONE
            return
        }
        binding.loadingGroup.visibility = View.GONE
        binding.contentGroup.visibility = View.VISIBLE

        val book = state.book ?: return

        // Update local chapter list — used by all position calculations
        if (state.chapters.isNotEmpty() && state.chapters != chapters) {
            chapters = state.chapters
            chapterAdapter.submitList(chapters)
            // Push updated chapters to the service if it's running
            AudiobookPlaybackService.pendingChapters = chapters
        }

        maybeStartPlayback()

        binding.tvTitle.text = book.title
        binding.tvAuthor.text = book.author ?: ""
        binding.btnSpeed.text = "${state.playbackSpeed}x"
        binding.tvOfflineBadge.visibility = if (state.isOffline) View.VISIBLE else View.GONE
        binding.tvSkipBackLabel.text = "${viewModel.session.skipBackSec}s"
        binding.tvSkipForwardLabel.text = "${viewModel.session.skipForwardSec}s"

        val thumbUrl = viewModel.buildThumbUrl(book.thumbPath)
        if (thumbUrl != null) {
            Glide.with(this).load(thumbUrl)
                .placeholder(R.drawable.ic_book_placeholder)
                .into(binding.ivCover)
        }

        binding.rvChapters.visibility = if (chapters.isNotEmpty()) View.VISIBLE else View.GONE

        val isPlaying = mediaController?.playbackState?.state == PlaybackStateCompat.STATE_PLAYING
        binding.btnPlayPause.setIconResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
        else "%d:%02d".format(m, sec)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
