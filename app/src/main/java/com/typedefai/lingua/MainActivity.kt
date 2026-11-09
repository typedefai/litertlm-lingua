package com.typedefai.lingua

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.typedefai.lingua.ui.App
import com.typedefai.lingua.ui.theme.LinguaTheme

class MainActivity : ComponentActivity() {

  private val viewModel: AppViewModel by viewModels()

  private val micPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {
      // The ViewModel re-checks the permission when the orb is pressed.
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // The in-app loading screen shows per-stage status; do not hold the system splash for the
    // whole model load, or those messages would never be visible.
    requestMicrophonePermissionIfNeeded()
    setContent {
      LinguaTheme {
        App(viewModel)
      }
    }
  }

  private fun requestMicrophonePermissionIfNeeded() {
    if (
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }
}
