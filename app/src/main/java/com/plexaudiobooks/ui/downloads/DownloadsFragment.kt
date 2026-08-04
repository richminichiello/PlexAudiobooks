package com.plexaudiobooks.ui.downloads

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.plexaudiobooks.databinding.FragmentDownloadsBinding
import com.plexaudiobooks.ui.playback.PlaybackManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DownloadsViewModel by viewModels()
    private lateinit var adapter: DownloadsAdapter
    @Inject lateinit var playbackManager: PlaybackManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        adapter = DownloadsAdapter(
            onPlay = { item ->
                playbackManager.play(item.ratingKey)
                (requireActivity() as? com.plexaudiobooks.ui.MainActivity)?.showPlayerSheet()
            },
            onDelete = { item ->
                viewModel.deleteDownload(item.ratingKey)
            },
            thumbUrlBuilder = { path -> viewModel.buildThumbUrl(path) }
        )

        binding.rvDownloads.apply {
            adapter = this@DownloadsFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        viewModel.downloads.observe(viewLifecycleOwner) { downloads ->
            adapter.submitList(downloads)
            binding.tvEmpty.isVisible = downloads.isEmpty()
            binding.tvStorageInfo.text = viewModel.getStorageSummary(downloads)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
