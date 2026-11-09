package com.typedefai.lingua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typedefai.lingua.AppViewModel
import com.typedefai.lingua.AssistantState
import com.typedefai.lingua.MicTarget
import com.typedefai.lingua.R

/**
 * Practice tab: the chat screen with Lily, the English tutor. Reuses the scaffold's voice loop:
 * hold the mic, stream the reply, hear it spoken while it is still generating.
 */
@Composable
fun PracticeScreen(viewModel: AppViewModel) {
  val messages by viewModel.messages.collectAsStateWithLifecycle()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val welcomeText by viewModel.welcomeText.collectAsStateWithLifecycle()
  var input by rememberSaveable { mutableStateOf("") }
  val listState = rememberLazyListState()
  val focusManager = LocalFocusManager.current

  LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
    if (messages.isNotEmpty()) {
      listState.scrollToItem(messages.lastIndex)
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .imePadding(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.practice_title),
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          text = stringResource(R.string.practice_subtitle),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Text(
          text = stringResource(R.string.backend_chip),
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(
        onClick = {
          input = ""
          focusManager.clearFocus()
          viewModel.newChat()
        },
        enabled = messages.isNotEmpty() || state != AssistantState.Idle,
      ) {
        Icon(
          imageVector = Icons.Rounded.Add,
          contentDescription = stringResource(R.string.practice_new_chat),
          tint = if (messages.isNotEmpty() || state != AssistantState.Idle) {
            MaterialTheme.colorScheme.onSurface
          } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
          },
        )
      }
    }

    if (messages.isEmpty()) {
      EmptyOrb(
        modifier = Modifier.weight(1f),
        title = stringResource(R.string.practice_empty_title),
        hint = welcomeText.ifBlank { stringResource(R.string.practice_empty_hint) },
      )
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        items(messages, key = { it.id }) { message ->
          val call = message.toolCall
          if (call != null) {
            ToolCallCard(call)
            return@items
          }
          MessageBubble(
            message = message,
            onStopSpeaking = viewModel::stopSpeaking,
          )
        }
      }
    }

    Composer(
      state = state,
      value = input,
      onValueChange = { input = it },
      onSend = {
        val text = input.trim()
        if (text.isNotEmpty()) {
          input = ""
          focusManager.clearFocus()
          viewModel.practiceSend(text)
        }
      },
      onMicPressStart = { viewModel.startListening(MicTarget.Practice) },
      onMicPressEnd = viewModel::stopAndTranscribe,
      onMicPressCancel = viewModel::cancelListening,
    )
  }
}
