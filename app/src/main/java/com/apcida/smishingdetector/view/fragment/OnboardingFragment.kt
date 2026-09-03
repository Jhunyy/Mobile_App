package com.apcida.smishingdetector.view.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.databinding.ActivityOnboardingBinding
import com.apcida.smishingdetector.util.PermissionHelper

class OnboardingFragment : Fragment() {

    private var _binding: ActivityOnboardingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // If permissions already granted skip onboarding
        if (PermissionHelper.hasSmsPermissions(requireContext())) {
            showVerifiedState()
            return
        }

        setupButtons()
    }

    private fun setupButtons() {
        binding.apply {

            // Grant permission button
            btnGrantPermission.setOnClickListener {
                PermissionHelper.requestSmsPermissions(requireActivity())
            }

            // Skip button
            textSkip.setOnClickListener {
                navigateToMessages()
            }

            // Done button (shown after verification)
            btnDone.setOnClickListener {
                navigateToMessages()
            }
        }
    }

    /**
     * Called when user grants permission.
     * Shows the verification complete state.
     */
    fun onPermissionGranted() {
        showVerifiedState()
    }

    private fun showVerifiedState() {
        binding.apply {
            // Hide permission request UI
            imgShield.visibility = View.GONE
            textTitle.visibility = View.GONE
            textDescription.visibility = View.GONE
            cardPrivacy.visibility = View.GONE
            btnGrantPermission.visibility = View.GONE
            textSkip.visibility = View.GONE

            // Show verified state
            layoutVerified.visibility = View.VISIBLE
        }
    }

    private fun navigateToMessages() {
        findNavController().navigate(R.id.messagesFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
