package com.typedefai.lingua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typedefai.lingua.AppViewModel
import com.typedefai.lingua.LinguaTab
import com.typedefai.lingua.R

private data class TabSpec(
  val tab: LinguaTab,
  val labelRes: Int,
  val icon: ImageVector,
)

private val TABS = listOf(
  TabSpec(LinguaTab.Translate, R.string.tab_translate, Icons.Rounded.Translate),
  TabSpec(LinguaTab.Dictionary, R.string.tab_dictionary, Icons.Rounded.Book),
  TabSpec(LinguaTab.Wordbook, R.string.tab_wordbook, Icons.Rounded.Bookmarks),
  TabSpec(LinguaTab.Practice, R.string.tab_practice, Icons.Rounded.ChatBubbleOutline),
)

/**
 * Main shell: a header with the app name and settings gear, the active tab's screen, and the
 * bottom navigation bar.
 */
@Composable
fun MainScreen(viewModel: AppViewModel) {
  val tab by viewModel.tab.collectAsStateWithLifecycle()
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  var showSettings by rememberSaveable { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    bottomBar = {
      NavigationBar {
        TABS.forEach { spec ->
          NavigationBarItem(
            selected = tab == spec.tab,
            onClick = {
              focusManager.clearFocus()
              viewModel.selectTab(spec.tab)
            },
            icon = { Icon(spec.icon, contentDescription = null) },
            label = { Text(stringResource(spec.labelRes)) },
          )
        }
      }
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .systemBarsPadding()
        .padding(innerPadding),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
          )
          Text(
            text = stringResource(
              R.string.header_subtitle,
              settings.audio.asrModel.title,
              settings.audio.ttsModel.title,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
        }
        IconButton(
          onClick = {
            focusManager.clearFocus()
            showSettings = true
          },
        ) {
          Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.settings),
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }

      when (tab) {
        LinguaTab.Translate -> TranslateScreen(viewModel)
        LinguaTab.Dictionary -> DictionaryScreen(viewModel)
        LinguaTab.Wordbook -> WordbookScreen(viewModel)
        LinguaTab.Practice -> PracticeScreen(viewModel)
      }
    }
  }

  if (showSettings) {
    SettingsDialog(
      initial = settings,
      onDismiss = { showSettings = false },
      onSave = { updated ->
        showSettings = false
        viewModel.applySettings(updated)
      },
    )
  }
}
