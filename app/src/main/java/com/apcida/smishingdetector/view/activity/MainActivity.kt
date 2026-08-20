package com.apcida.smishingdetector.view.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.database.DatabaseSeeder
import com.apcida.smishingdetector.backend.gemma.GemmaValidator
import com.apcida.smishingdetector.backend.network.ReportUploadManager
import com.apcida.smishingdetector.databinding.ActivityMainBinding
import com.apcida.smishingdetector.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var gemmaValidator: GemmaValidator
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
        requestSmsPermissions()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)
    }

    private fun seedDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getInstance(applicationContext)
                val seeder = DatabaseSeeder(applicationContext, database)
                seeder.seedIfFirstLaunch()
                Log.d(TAG, "Database seeding completed.")
            } catch (e: Exception) {
                Log.e(TAG, "Database seeding failed: ${e.message}")
            }
        }
    }

    private fun loadGemmaModel() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                gemmaValidator = GemmaValidator(applicationContext)
                gemmaValidator.loadModel()
                Log.d(TAG, "Gemma model loaded.")
            } catch (e: Exception) {
                Log.e(TAG, "Gemma model load failed: ${e.message}")
            }
        }
    }

    private fun uploadPendingReports() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reportUploadManager.uploadPendingReports()
            } catch (e: Exception) {
                Log.e(TAG, "Report upload failed: ${e.message}")
            }
        }
    }

    private fun requestSmsPermissions() {
        if (!PermissionHelper.hasSmsPermissions(this)) {
            PermissionHelper.requestSmsPermissions(this)
        } else {
            Log.d(TAG, "SMS permissions already granted.")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PermissionHelper.SMS_PERMISSION_REQUEST_CODE) {
            if (PermissionHelper.allPermissionsGranted(grantResults)) {
                Log.d(TAG, "All SMS permissions granted.")

                val navHostFragment = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment)
                val currentFragment = navHostFragment
                    ?.childFragmentManager
                    ?.primaryNavigationFragment

                if (currentFragment is OnboardingActivity) {
                    currentFragment.onPermissionGranted()
                }

            } else {
                Log.w(TAG, "SMS permissions denied.")
                if (!PermissionHelper.hasSmsPermissions(this)) {
                    navController.navigate(R.id.onboardingFragment)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::gemmaValidator.isInitialized) {
            gemmaValidator.release()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}