package com.plexaudiobooks.ui.auth

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.plexaudiobooks.R
import com.plexaudiobooks.databinding.FragmentAuthBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AuthFragment : Fragment() {

    private var _binding: FragmentAuthBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AuthViewModel by viewModels()

    private var customTabsClient: CustomTabsClient? = null
    private var customTabsSession: CustomTabsSession? = null
    private val customTabsConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            customTabsClient = client
            client.warmup(0)
            customTabsSession = client.newSession(object : CustomTabsCallback() {})
        }
        override fun onServiceDisconnected(name: ComponentName) {
            customTabsClient = null
            customTabsSession = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Warm up Custom Tab in background for faster launch
        CustomTabsClient.bindCustomTabsService(
            requireContext(), "com.android.chrome", customTabsConnection
        )

        binding.btnSignIn.setOnClickListener { viewModel.startAuth() }
        binding.btnCancel.setOnClickListener { viewModel.cancelAuth() }

        viewModel.authState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSignIn.visibility = View.VISIBLE
                    binding.btnCancel.visibility = View.GONE
                    binding.tvStatus.text = ""
                }
                is AuthState.LoadingPin -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnSignIn.visibility = View.GONE
                    binding.btnCancel.visibility = View.GONE
                    binding.tvStatus.text = getString(R.string.auth_preparing)
                }
                is AuthState.WaitingForAuth -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnSignIn.visibility = View.GONE
                    binding.btnCancel.visibility = View.VISIBLE
                    binding.tvStatus.text = getString(R.string.auth_waiting)
                    openAuthInCustomTab(state.authUrl)
                }
                is AuthState.Polling -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnSignIn.visibility = View.GONE
                    binding.btnCancel.visibility = View.VISIBLE
                    // User has returned from browser — update status
                    binding.tvStatus.text = getString(R.string.auth_verifying)
                }
                is AuthState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    // Skip server setup — go straight to server selection
                    findNavController().navigate(R.id.action_auth_to_serverSetup)
                }
                is AuthState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSignIn.visibility = View.VISIBLE
                    binding.btnCancel.visibility = View.GONE
                    binding.tvStatus.text = ""
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openAuthInCustomTab(url: String) {
        val colorParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary))
            .build()

        val intent = CustomTabsIntent.Builder(customTabsSession)
            .setDefaultColorSchemeParams(colorParams)
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()

        // The polling loop continues in the ViewModel while the user is in the
        // Custom Tab. When they complete sign-in, app.plex.tv shows "You may now
        // close this window." The user closes it manually, returns here, and the
        // polling detects the token automatically — no deep link needed.
        intent.launchUrl(requireContext(), Uri.parse(url))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { requireContext().unbindService(customTabsConnection) } catch (_: Exception) {}
        _binding = null
    }
}
