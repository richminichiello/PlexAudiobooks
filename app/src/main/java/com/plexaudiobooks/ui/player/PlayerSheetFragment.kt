package com.plexaudiobooks.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.plexaudiobooks.R
import com.plexaudiobooks.data.model.Chapter
import com.plexaudiobooks.databinding.FragmentPlayerBinding
import com.plexaudiobooks.ui.playback.PlaybackManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The expanded Now Playing view, shown as a bottom-sheet overlay from MainActivity.
 * It is NOT a nav destination and never lives on the back stack —
 * swipe-down or back dismisses it. All its state comes from the singleton [PlaybackManager];
 * there is no per-entry loadBook() re-probe and no fresh MediaBrowser connection (the
 * "playback dies, can't restart" root cause is gone because this fragment has no controller
 * lifecycle of its own).
 */
@AndroidEntryPoint
@androidx.media3.common.util.UnstableApi
class PlayerSheetFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var playbackManager: PlaybackManager

    private lateinit var chapterAdapter: ChapterAdapter
    private var chapters: List<Chapter> = emptyList()

    // System-back inside the sheet dismisses it (handled by MainActivity's back callback).
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChapterList()
        setupControls()
        observeState()
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun setupChapterList() {
        chapterAdapter = ChapterAdapter { chapter ->
            val idx = chapters.indexOf(chapter).toLong()
            if (idx >= 0) playbackManager.seekAbsolute(chapter.startMs)
        }
        binding.rvChapters.apply {
            adapter = chapterAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { dismiss() }

        binding.btnPlayPause.setOnClickListener { playbackManager.togglePlayPause() }

        binding.btnSkipBack.setOnClickListener {
            playbackManager.skipBy(-(playbackManager.session.skipBackSec * 1000L))
        }
        binding.btnSkipForward.setOnClickListener {
            playbackManager.skipBy(playbackManager.session.skipForwardSec * 1000L)
        }

        binding.btnPrevChapter.setOnClickListener { playbackManager.previousChapter() }
        binding.btnNextChapter.setOnClickListener { playbackManager.nextChapter() }

        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        binding.btnSpeed.setOnClickListener {
            val current = playbackManager.state.value.playbackSpeed
            val next = speeds[(speeds.indexOf(current) + 1) % speeds.size]
            playbackManager.setSpeed(next)
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val chapter = currentChapter()
                    val chapterDur = if (chapter != null) chapter.endMs - chapter.startMs else 1L
                    binding.tvPosition.text = formatMs((progress / 1000f * chapterDur).toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val chapter = currentChapter()
                val absSeek = if (chapter != null) {
                    val chapterDur = chapter.endMs - chapter.startMs
                    chapter.startMs + (seekBar.progress / 1000f * chapterDur).toLong()
                } else {
                    val bookDur = playbackManager.state.value.bookDurationMs
                    if (bookDur > 0) (seekBar.progress / 1000f * bookDur).toLong() else return
                }
                playbackManager.seekAbsolute(absSeek)
            }
        })
    }

    private fun currentChapter(): Chapter? = chapters.lastOrNull {
        playbackManager.state.value.positionMs >= it.startMs
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playbackManager.state.collect { state ->
                    render(state)
                }
            }
        }
        // Lightweight 500ms refresh so the seekbar advances while playing (the manager also
        // polls, but the sheet's own tick keeps the UI independent of callback timing).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    render(playbackManager.state.value)
                    delay(500)
                }
            }
        }
    }

    private fun render(state: com.plexaudiobooks.ui.playback.NowPlayingUiState) {
        // Loading spinner: visible only until the book arrives. The ProgressBar is
        // centered over the whole sheet by default (fragment_player.xml) and was
        // never hidden, so it sat on top of the chapter list forever.
        binding.loadingGroup.isVisible = state.book == null
        val book = state.book ?: run {
            binding.contentGroup.isVisible = false
            return
        }
        binding.contentGroup.isVisible = true

        if (state.chapters.isNotEmpty() && state.chapters != chapters) {
            chapters = state.chapters
            chapterAdapter.submitList(chapters)
        }

        binding.tvTitle.text = book.title
        binding.tvAuthor.text = book.author ?: ""
        binding.btnSpeed.text = "${state.playbackSpeed}x"
        binding.tvOfflineBadge.isVisible = state.isOffline
        binding.tvSkipBackLabel.text = "${playbackManager.session.skipBackSec}s"
        binding.tvSkipForwardLabel.text = "${playbackManager.session.skipForwardSec}s"

        val thumbUrl = playbackManager.session.buildThumbUrl(book.thumbPath)
        if (thumbUrl != null) {
            Glide.with(this).load(thumbUrl)
                .placeholder(R.drawable.ic_book_placeholder).into(binding.ivCover)
        }
        binding.rvChapters.isVisible = chapters.isNotEmpty()

        // Chapter-relative seekbar
        val chapter = chapters.lastOrNull { state.positionMs >= it.startMs }
        if (chapter != null && chapter.endMs > chapter.startMs) {
            val chapterDur = chapter.endMs - chapter.startMs
            val chapterRel = (state.positionMs - chapter.startMs).coerceIn(0L, chapterDur)
            if (!binding.seekBar.isPressed) {
                binding.seekBar.progress = ((chapterRel.toFloat() / chapterDur) * 1000)
                    .toInt().coerceIn(0, 1000)
            }
            binding.tvPosition.text = formatMs(chapterRel)
            binding.tvDuration.text = formatMs(chapterDur)
            binding.tvCurrentChapter.text = chapter.title
            chapterAdapter.setCurrentChapter(chapters.indexOfLast { state.positionMs >= it.startMs })
        } else if (state.bookDurationMs > 0) {
            if (!binding.seekBar.isPressed) {
                binding.seekBar.progress = ((state.positionMs.toFloat() / state.bookDurationMs) * 1000)
                    .toInt().coerceIn(0, 1000)
            }
            binding.tvPosition.text = formatMs(state.positionMs)
            binding.tvDuration.text = formatMs(state.bookDurationMs)
        }

        val remaining = state.bookRemainingMs
        binding.tvTotalRemaining.text =
            if (remaining > 0) "${formatMs(remaining)} remaining in book" else ""

        binding.btnPlayPause.setIconResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlayPause.isEnabled = !state.isBuffering
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
