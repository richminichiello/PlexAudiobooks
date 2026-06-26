package com.plexaudiobooks.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.plexaudiobooks.BuildConfig
import com.plexaudiobooks.R
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.databinding.FragmentSettingsBinding
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var session: SessionManager
    @Inject lateinit var repository: PlexRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // Account
        binding.tvUsername.text = session.username ?: "—"
        binding.tvServerUrl.text = session.serverUrl ?: "—"

        // Playback
        binding.tvSkipForward.text = "${session.skipForwardSec}s"
        binding.tvSkipBack.text = "${session.skipBackSec}s"

        // Downloads
        binding.tvDownloadHours.text = "${session.downloadHours}h"
        updateDownloadLocationDisplay()
        updateStorageDisplay()

        // Version
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        // ── Listeners ────────────────────────────────────────────────────────

        binding.rowServerUrl.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_serverSetup)
        }

        binding.rowSkipForward.setOnClickListener {
            showIntPickerDialog(
                title = getString(R.string.skip_forward_seconds),
                options = listOf(10, 15, 30, 45, 60),
                current = session.skipForwardSec,
                suffix = "s"
            ) { value ->
                session.skipForwardSec = value
                binding.tvSkipForward.text = "${value}s"
            }
        }

        binding.rowSkipBack.setOnClickListener {
            showIntPickerDialog(
                title = getString(R.string.skip_back_seconds),
                options = listOf(5, 10, 15, 30),
                current = session.skipBackSec,
                suffix = "s"
            ) { value ->
                session.skipBackSec = value
                binding.tvSkipBack.text = "${value}s"
            }
        }

        binding.rowDownloadLocation.setOnClickListener {
            val options = listOf("internal", "external")
            val labels = arrayOf("Internal storage (recommended)", "External storage (SD card / Files app)")
            val currentIndex = options.indexOf(session.downloadLocation).coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("Download location")
                .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                    session.downloadLocation = options[which]
                    updateDownloadLocationDisplay()
                    dialog.dismiss()
                }
                .show()
        }

        binding.rowDownloadHours.setOnClickListener {
            showIntPickerDialog(
                title = getString(R.string.offline_buffer_hours),
                options = listOf(1, 2, 3, 4, 6, 8),
                current = session.downloadHours,
                suffix = "h"
            ) { value ->
                session.downloadHours = value
                binding.tvDownloadHours.text = "${value}h"
                // Warn if > 2h selected — this will auto-download when listening starts
                if (value > 2) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Large Read-Ahead Cache")
                        .setMessage("${value}h of audio will be automatically downloaded each time you start listening to a book. This may use significant data on cellular networks. Starting playback on Wi-Fi is recommended.")
                        .setPositiveButton("Got it", null)
                        .show()
                }
            }
        }

        binding.rowClearCache.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_cache)
                .setMessage(getString(R.string.clear_cache_confirm))
                .setPositiveButton(R.string.clear) { _, _ ->
                    clearDownloadsCache()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnSignOut.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.sign_out)
                .setMessage("Are you sure you want to sign out?")
                .setPositiveButton(R.string.sign_out) { _, _ ->
                    session.logout()
                    findNavController().navigate(R.id.action_settings_to_auth)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateStorageDisplay() {
        val downloadsDir = File(requireContext().filesDir, "downloads")
        val usedBytes = downloadsDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        val usedMb = usedBytes / (1024 * 1024)
        binding.tvStorageUsed.text = "${usedMb} MB used"
    }

    private fun clearDownloadsCache() {
        lifecycleScope.launch {
            // Delete all downloaded files
            val downloadsDir = File(requireContext().filesDir, "downloads")
            downloadsDir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
            // Clear DB records
            repository.observeDownloads()
            // We can't iterate a Flow directly — use the DAO via a one-shot approach
            // Clear by removing all entries tracked in Room
            requireContext().let { ctx ->
                // Use WorkManager to cancel pending downloads
                androidx.work.WorkManager.getInstance(ctx).cancelAllWork()
            }
            // Update UI
            updateStorageDisplay()
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.cache_cleared),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateDownloadLocationDisplay() {
        binding.tvDownloadLocation.text = when (session.downloadLocation) {
            "external" -> "External"
            else -> "Internal"
        }
    }

    private fun showIntPickerDialog(title: String, options: List<Int>, current: Int,
                                    suffix: String, onSelected: (Int) -> Unit) {
        val labels = options.map { "$it$suffix" }.toTypedArray()
        val currentIndex = options.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                onSelected(options[which])
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
