package com.typedefai.lingua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typedefai.lingua.AppViewModel
import com.typedefai.lingua.R

@Composable
fun App(viewModel: AppViewModel) {
  val isReady by viewModel.isReady.collectAsStateWithLifecycle()
  val loadingStatus by viewModel.loadingStatus.collectAsStateWithLifecycle()
  if (isReady) {
    MainScreen(viewModel)
  } else {
    LoadingScreen(
      status = loadingStatus.ifBlank { stringResource(R.string.splash_loading) },
    )
  }
}

/**
 * Fallback loading UI. The Android 12+ system splash normally covers this window while
 * [AppViewModel.isReady] is false.
 */
@Composable
private fun LoadingScreen(status: String) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator()
      Spacer(Modifier.height(16.dp))
      Text(
        text = status,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp),
        textAlign = TextAlign.Center,
      )
    }
  }
}
