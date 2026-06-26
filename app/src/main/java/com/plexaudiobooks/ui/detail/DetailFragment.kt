package com.plexaudiobooks.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.databinding.FragmentDetailBinding
import com.plexaudiobooks.service.BookDownloadWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DetailViewModel by viewModels()
    private val args: DetailFragmentArgs by navArgs()

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startDownload()
            observeDownloadWork()
        } else {
            Toast.makeText(requireContext(),
                "Storage permission needed to download books", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        viewModel.loadBook(args.ratingKey)

        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                updateUi(state)
            }
        }

        binding.btnPlay.setOnClickListener {
            findNavController().navigate(
                DetailFragmentDirections.actionDetailToPlayer(args.ratingKey)
            )
        }

        binding.btnDownload.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isDownloaded) {
                viewModel.deleteDownload()
            } else {
                requestStorageAndDownload()
            }
        }

        observeDownloadWork()
    }

    private fun requestStorageAndDownload() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ uses READ_MEDIA_AUDIO; files go to app-private dir, no permission needed
                viewModel.startDownload()
                observeDownloadWork()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10-12: app-private internal storage needs no permission
                viewModel.startDownload()
                observeDownloadWork()
            }
            else -> {
                // Android 9 and below: need WRITE_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startDownload()
                    observeDownloadWork()
                } else {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun observeDownloadWork() {
        WorkManager.getInstance(requireContext())
            .getWorkInfosByTagLiveData(args.ratingKey)
            .observe(viewLifecycleOwner) { workInfos ->
                val info = workInfos?.firstOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = info.progress.getInt(BookDownloadWorker.KEY_PROGRESS, 0)
                        // Show circular spinner overlay, hide button text
                        binding.btnDownload.isVisible = false
                        binding.downloadingGroup.isVisible = true
                        binding.downloadSpinner.progress = progress
                        binding.tvDownloadPercent.text = "$progress%"
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        binding.downloadingGroup.isVisible = false
                        binding.btnDownload.isVisible = true
                        binding.btnDownload.setText(R.string.remove_download)
                        viewModel.markDownloaded()
                    }
                    WorkInfo.State.FAILED -> {
                        binding.downloadingGroup.isVisible = false
                        binding.btnDownload.isVisible = true
                        binding.btnDownload.setText(R.string.download)
                        val errorMsg = info.outputData.getString(
                            BookDownloadWorker.KEY_ERROR
                        ) ?: getString(R.string.download_failed)
                        Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        binding.downloadingGroup.isVisible = false
                        binding.btnDownload.isVisible = true
                    }
                }
            }
    }

    private fun updateUi(state: DetailUiState) {
        binding.loadingGroup.isVisible = state.isLoading
        binding.contentGroup.isVisible = !state.isLoading && state.error == null

        state.error?.let {
            Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
            return
        }

        val book = state.book ?: return
        binding.tvTitle.text = book.title
        binding.collapsingToolbar.title = book.title
        binding.tvAuthor.text = book.author ?: ""
        binding.tvSummary.text = book.summary ?: getString(R.string.no_summary)
        binding.tvDuration.text = formatMs(book.duration)

        if (book.viewOffset > 0) {
            binding.progressGroup.isVisible = true
            binding.progressBar.progress = (book.progressPercent * 100).toInt()
            binding.tvProgress.text = getString(
                R.string.progress_format,
                formatMs(book.viewOffset),
                formatMs(book.duration)
            )
        } else {
            binding.progressGroup.isVisible = false
        }

        val thumbUrl = viewModel.buildThumbUrl(book.thumbPath)
        Glide.with(this)
            .load(thumbUrl)
            .placeholder(R.drawable.ic_book_placeholder)
            .into(binding.ivCover)

        binding.btnDownload.setText(
            if (state.isDownloaded) R.string.remove_download else R.string.download
        )
        binding.btnDownload.setIconResource(
            if (state.isDownloaded) R.drawable.ic_offline_available
            else R.drawable.ic_download
        )

        if (state.chapters.isNotEmpty()) {
            binding.tvChaptersHeader.isVisible = true
            binding.tvChaptersCount.text = getString(R.string.chapters_count, state.chapters.size)
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return if (hours > 0) getString(R.string.duration_hours_mins, hours, minutes)
        else getString(R.string.duration_mins, minutes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
