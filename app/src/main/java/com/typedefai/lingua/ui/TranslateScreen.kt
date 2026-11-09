package com.typedefai.lingua.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typedefai.lingua.AppViewModel
import com.typedefai.lingua.AssistantState
import com.typedefai.lingua.MicTarget
import com.typedefai.lingua.R
import com.typedefai.lingua.TranslateUiState
import com.typedefai.lingua.lingua.TranslateMode

/**
 * Translate tab: source box with hold-to-talk mic, language-direction chips, and a streamed
 * result card with speak/copy/explain actions.
 */
@Composable
fun TranslateScreen(viewModel: AppViewModel) {
  val translate by viewModel.translate.collectAsStateWithLifecycle()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val focusManager = LocalFocusManager.current

  Column(
    modifier = Modifier
      .fillMaxSize()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    DirectionRow(
      mode = translate.mode,
      onModeChange = viewModel::setTranslateMode,
    )

    SourceCard(
      input = translate.input,
      busy = translate.busy,
      listening = state == AssistantState.Listening,
      onValueChange = viewModel::updateTranslateInput,
      onTranslate = {
        focusManager.clearFocus()
        viewModel.translate()
      },
      onMicPressStart = { viewModel.startListening(MicTarget.Translate) },
      onMicPressEnd = viewModel::stopAndTranscribe,
      onMicPressCancel = viewModel::cancelListening,
      onClear = {
        viewModel.updateTranslateInput("")
        viewModel.stopSpeaking()
      },
    )

    translate.note?.let { note ->
      Text(
        text = note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }

    translate.error?.let { error ->
      Text(
        text = error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }

    ResultCard(
      translate = translate,
      onSpeak = viewModel::speakTranslation,
      onStopSpeaking = viewModel::stopSpeaking,
      onExplain = viewModel::explainTranslation,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.translate_auto_speak),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      Switch(
        checked = translate.autoSpeak,
        onCheckedChange = viewModel::setAutoSpeak,
      )
    }
  }
}

@Composable
private fun DirectionRow(mode: TranslateMode, onModeChange: (TranslateMode) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FilterChip(
      selected = mode == TranslateMode.AUTO,
      onClick = { onModeChange(TranslateMode.AUTO) },
      label = { Text(TranslateMode.AUTO.label) },
    )
    FilterChip(
      selected = mode == TranslateMode.EN_TO_ZH,
      onClick = { onModeChange(TranslateMode.EN_TO_ZH) },
      label = { Text(TranslateMode.EN_TO_ZH.label) },
    )
    FilterChip(
      selected = mode == TranslateMode.ZH_TO_EN,
      onClick = { onModeChange(TranslateMode.ZH_TO_EN) },
      label = { Text(TranslateMode.ZH_TO_EN.label) },
    )
  }
}

@Composable
private fun SourceCard(
  input: String,
  busy: Boolean,
  listening: Boolean,
  onValueChange: (String) -> Unit,
  onTranslate: () -> Unit,
  onMicPressStart: () -> Unit,
  onMicPressEnd: () -> Unit,
  onMicPressCancel: () -> Unit,
  onClear: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      BasicTextField(
        value = input,
        onValueChange = onValueChange,
        enabled = !busy,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
          color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onTranslate() }),
        maxLines = 8,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 96.dp),
        decorationBox = { innerTextField ->
          Box(contentAlignment = Alignment.TopStart) {
            if (input.isEmpty()) {
              Text(
                text = stringResource(R.string.translate_input_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            innerTextField()
          }
        },
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (input.isNotEmpty()) {
          IconButton(onClick = onClear) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = stringResource(R.string.translate_clear),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Spacer(Modifier.weight(1f))
        if (busy) {
          CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
        }
        MicButton(
          state = when {
            listening -> AssistantState.Listening
            busy -> AssistantState.Thinking
            else -> AssistantState.Idle
          },
          onPressStart = onMicPressStart,
          onPressEnd = onMicPressEnd,
          onPressCancel = onMicPressCancel,
          contentDescription = stringResource(R.string.translate_mic_description),
        )
        Spacer(Modifier.width(8.dp))
        Button(
          onClick = onTranslate,
          enabled = input.isNotBlank() && !busy,
        ) {
          Icon(
            imageVector = Icons.Rounded.Translate,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.translate_button))
        }
      }
    }
  }
}

@Composable
private fun ResultCard(
  translate: TranslateUiState,
  onSpeak: () -> Unit,
  onStopSpeaking: () -> Unit,
  onExplain: () -> Unit,
) {
  val context = LocalContext.current
  val clipboard = remember(context) {
    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  }
  val targetLabel = translate.targetLang?.label
  val hasOutput = translate.output.isNotBlank()

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = targetLabel ?: stringResource(R.string.translate_result),
          style = MaterialTheme.typography.titleSmall,
        )
        if (translate.busy) {
          Spacer(Modifier.width(8.dp))
          CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
          )
        }
        Spacer(Modifier.weight(1f))
        if (hasOutput) {
          if (translate.isSpeaking) {
            TextButton(onClick = onStopSpeaking) {
              Text(stringResource(R.string.speaking_tap_to_stop))
            }
          } else {
            IconButton(onClick = onSpeak) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = stringResource(R.string.translate_speak),
              )
            }
          }
          IconButton(
            onClick = {
              clipboard.setPrimaryClip(ClipData.newPlainText("translation", translate.output))
            },
          ) {
            Icon(
              imageVector = Icons.Rounded.ContentCopy,
              contentDescription = stringResource(R.string.copy),
            )
          }
        }
      }
      if (hasOutput) {
        SelectionContainer {
          Text(
            text = translate.output,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
      } else if (!translate.busy) {
        Text(
          text = stringResource(R.string.translate_result_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
          modifier = Modifier.padding(top = 4.dp),
        )
      } else {
        Text(
          text = stringResource(R.string.translating),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      if (hasOutput && translate.explanation.isBlank() && !translate.explaining) {
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onExplain) {
          Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.translate_explain))
        }
      }

      if (translate.explaining || translate.explanation.isNotBlank()) {
        HorizontalDivider(
          modifier = Modifier.padding(vertical = 8.dp),
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
        )
        Text(
          text = stringResource(R.string.translate_explanation_title),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
        SelectionContainer {
          Text(
            text = translate.explanation.ifBlank { stringResource(R.string.thinking) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
      }
    }
  }
}
