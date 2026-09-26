package com.apcida.smishingdetector.view.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.database.DatabaseSeeder
import com.apcida.smishingdetector.backend.gemma.GemmaManager
import com.apcida.smishingdetector.backend.network.ReportUploadManager
import com.apcida.smishingdetector.databinding.ActivityMainBinding
import com.apcida.smishingdetector.util.PermissionHelper
import com.apcida.smishingdetector.view.fragment.OnboardingFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private val reportUploadManager by lazy {
        ReportUploadManager(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        seedDatabase()
        loadGemmaModel()
        uploadPendingReports()
        requestRequiredPermissions()
    }

    /**
     * Sets up bottom navigation without transition animations.
     */
    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        navController = navHostFragment.navController

        binding.bottomNavigation.setOnItemSelectedListener { item ->

            // Don't navigate again if already on this destination.
            if (navController.currentDestination?.id == item.itemId) {
                return@setOnItemSelectedListener true
            }

            val navOptions = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(true)

                // Keep each bottom navigation destination state.
                .setPopUpTo(
                    navController.graph.findStartDestination().id,
                    inclusive = false,
                    saveState = true
                )

                // Remove all transition animations.
                .setEnterAnim(0)
                .setExitAnim(0)
                .setPopEnterAnim(0)
                .setPopExitAnim(0)
                .build()

            try {
                navController.navigate(
                    item.itemId,
                    null,
                    navOptions
                )

                true
            } catch (e: IllegalArgumentException) {
                Log.e(
                    TAG,
                    "Navigation failed for destination ${item.itemId}: ${e.message}"
                )
                false
            }
        }

        // Do nothing when the currently selected tab is tapped again.
        binding.bottomNavigation.setOnItemReselectedListener {
            // Intentionally empty.
        }
    }

    private fun seedDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getInstance(applicationContext)
                val seeder = DatabaseSeeder(applicationContext, database)

                seeder.seedIfFirstLaunch()

                Log.d(TAG, "Database seeding completed.")
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Database seeding failed: ${e.message}"
                )
            }
        }
    }

    private fun loadGemmaModel() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GemmaManager.loadModel(applicationContext)

                Log.d(TAG, "Gemma model loaded.")
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Gemma model load failed: ${e.message}"
                )
            }
        }
    }

    private fun uploadPendingReports() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reportUploadManager.uploadPendingReports()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Report upload failed: ${e.message}"
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        if (!PermissionHelper.hasSmsPermissions(this)) {

            PermissionHelper.requestSmsPermissions(this)

        } else {

            Log.d(TAG, "SMS permissions already granted.")

            requestNotificationPermissionIfNeeded()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (!PermissionHelper.hasNotificationPermission(this)) {

            PermissionHelper.requestNotificationPermission(this)

        } else {

            Log.d(
                TAG,
                "Notification permission already granted or not required."
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode ==
            PermissionHelper.SMS_PERMISSION_REQUEST_CODE
        ) {

            if (
                PermissionHelper
                    .allPermissionsGranted(grantResults)
            ) {

                Log.d(
                    TAG,
                    "All SMS permissions granted."
                )

                val navHostFragment =
                    supportFragmentManager
                        .findFragmentById(
                            R.id.nav_host_fragment
                        )

                val currentFragment =
                    navHostFragment
                        ?.childFragmentManager
                        ?.primaryNavigationFragment

                if (
                    currentFragment
                            is OnboardingFragment
                ) {

                    currentFragment
                        .onPermissionGranted()
                }

                requestNotificationPermissionIfNeeded()

            } else {

                Log.w(
                    TAG,
                    "SMS permissions denied."
                )

                if (
                    !PermissionHelper
                        .hasSmsPermissions(this)
                ) {

                    navController.navigate(
                        R.id.onboardingFragment
                    )
                }
            }

        } else if (
            requestCode ==
            PermissionHelper
                .NOTIFICATION_PERMISSION_REQUEST_CODE
        ) {

            if (
                PermissionHelper
                    .allPermissionsGranted(grantResults)
            ) {

                Log.d(
                    TAG,
                    "Notification permission granted."
                )

            } else {

                Log.w(
                    TAG,
                    "Notification permission denied."
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        GemmaManager.release()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() ||
                super.onSupportNavigateUp()
    }
}
