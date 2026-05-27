package com.plexaudiobooks.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.plexaudiobooks.R
import com.plexaudiobooks.data.model.PlexDirectory
import com.plexaudiobooks.data.model.PlexHomeUser
import com.plexaudiobooks.data.model.PlexResource
import com.plexaudiobooks.databinding.FragmentServerSetupBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ServerSetupFragment : Fragment() {

    private var _binding: FragmentServerSetupBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ServerSetupViewModel by viewModels()

    // Reuse the server adapter for home users and libraries too (same list UI)
    private lateinit var listAdapter: ServerListAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServerSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listAdapter = ServerListAdapter { /* clicks handled per-step below */ }

        binding.rvServers.apply {
            adapter = listAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        binding.btnManualEntry.setOnClickListener {
            binding.manualEntryGroup.isVisible = !binding.manualEntryGroup.isVisible
        }

        binding.btnConnect.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isBlank()) {
                binding.tilServerUrl.error = getString(R.string.error_url_required)
                return@setOnClickListener
            }
            viewModel.connectManually(url)
        }

        lifecycleScope.launch {
            viewModel.step.collectLatest { step ->
                renderStep(step)
            }
        }

        viewModel.start()
    }

    private fun renderStep(step: SetupStep) {
        // Reset visibility
        binding.progressBar.isVisible = step is SetupStep.Loading
        binding.rvServers.isVisible = false
        binding.tvNoServers.isVisible = false
        binding.btnManualEntry.isVisible = false
        binding.manualEntryGroup.isVisible = false

        when (step) {
            is SetupStep.Loading -> {
                binding.tvStatus.text = "Please wait…"
            }

            is SetupStep.PickHomeUser -> {
                binding.tvStatus.text = "Which profile would you like to use?"
                binding.rvServers.isVisible = true
                // Reuse the server adapter with a home user click handler
                val homeAdapter = HomeUserAdapter { user ->
                    viewModel.selectHomeUser(user)
                }
                binding.rvServers.adapter = homeAdapter
                homeAdapter.submitList(step.users)
            }

            is SetupStep.EnterPin -> {
                binding.tvStatus.text = "Enter PIN for ${step.user.title}"
                showPinDialog(step.user)
            }

            is SetupStep.PickServer -> {
                binding.tvStatus.text = "Select your Plex server:"
                binding.rvServers.isVisible = true
                binding.btnManualEntry.isVisible = true
                binding.rvServers.adapter = listAdapter
                listAdapter.submitList(step.servers)
                // Wire click
                listAdapter.setOnClickListener { server ->
                    viewModel.selectServer(server as PlexResource)
                }
            }

            is SetupStep.PickLibrary -> {
                binding.tvStatus.text = "Select which library to use on ${step.server.name}:"
                binding.rvServers.isVisible = true
                val libAdapter = LibraryAdapter { library ->
                    viewModel.selectLibrary(step.server, library)
                }
                binding.rvServers.adapter = libAdapter
                libAdapter.submitList(step.libraries)
            }

            is SetupStep.Error -> {
                binding.tvStatus.text = step.message
                binding.tvNoServers.isVisible = true
                binding.tvNoServers.text = step.message
                binding.btnManualEntry.isVisible = true
            }

            is SetupStep.Done -> {
                findNavController().navigate(R.id.action_serverSetup_to_library)
            }
        }
    }

    private fun showPinDialog(user: PlexHomeUser) {
        val pinInput = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter 4-digit PIN"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("PIN for ${user.title}")
            .setView(pinInput)
            .setPositiveButton("Sign In") { _, _ ->
                viewModel.submitPin(pinInput.text.toString())
            }
            .setNegativeButton("Cancel") { _, _ ->
                viewModel.start() // restart from home user pick
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
