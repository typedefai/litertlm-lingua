package com.typedefai.lingua.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.typedefai.lingua.AssistantState
import com.typedefai.lingua.ChatMessage
import com.typedefai.lingua.ChatRole
import com.typedefai.lingua.R

/** Chat bubble used by the practice screen. */
@Composable
fun MessageBubble(
  message: ChatMessage,
  onStopSpeaking: () -> Unit,
) {
  val isUser = message.role == ChatRole.User
  val context = LocalContext.current
  val clipboard = remember(context) {
    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  }
  val bubbleShape = if (isUser) {
    RoundedCornerShape(topStart = 20.dp, topEnd = 8.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
  } else {
    RoundedCornerShape(topStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
  }
  val containerColor = when {
    message.isError -> MaterialTheme.colorScheme.errorContainer
    isUser -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  val contentColor = when {
    message.isError -> MaterialTheme.colorScheme.onErrorContainer
    isUser -> MaterialTheme.colorScheme.onPrimary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val metricsText = when {
    isUser || message.isStreaming -> ""
    message.outputTokens != null && message.decodeSpeedTps != null ->
      stringResource(R.string.message_metrics, message.outputTokens, message.decodeSpeedTps)
    message.outputTokens != null ->
      stringResource(R.string.message_tokens, message.outputTokens)
    message.decodeSpeedTps != null ->
      stringResource(R.string.message_speed, message.decodeSpeedTps)
    else -> ""
  }

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
  ) {
    Text(
      text = stringResource(if (isUser) R.string.you else R.string.tutor),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
    Surface(
      shape = bubbleShape,
      color = containerColor,
      contentColor = contentColor,
      modifier = Modifier.widthIn(max = 340.dp),
    ) {
      Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        val text = when {
          message.text.isNotBlank() -> message.text
          message.isStreaming -> stringResource(R.string.thinking)
          else -> "…"
        }
        SelectionContainer {
          Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
          )
        }
        if (message.isStreaming && message.text.isBlank()) {
          Spacer(Modifier.height(8.dp))
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
      }
    }
    if (!isUser && message.isSpeaking) {
      Row(
        modifier = Modifier
          .clickable(onClick = onStopSpeaking)
          .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(4.dp))
        Text(
          text = stringResource(R.string.speaking_tap_to_stop),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
    if (!isUser && metricsText.isNotEmpty()) {
      Text(
        text = metricsText,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
      )
    }
    if (!isUser && message.text.isNotBlank() && !message.isStreaming) {
      IconButton(
        onClick = {
          clipboard.setPrimaryClip(ClipData.newPlainText("message", message.text))
        },
        modifier = Modifier.size(28.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.ContentCopy,
          contentDescription = stringResource(R.string.copy),
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
      }
    }
  }
}

/** Bottom composer with text field and the hold-to-talk mic. */
@Composable
fun Composer(
  state: AssistantState,
  value: String,
  onValueChange: (String) -> Unit,
  onSend: () -> Unit,
  onMicPressStart: () -> Unit,
  onMicPressEnd: () -> Unit,
  onMicPressCancel: () -> Unit,
) {
  val canType = state == AssistantState.Idle || state == AssistantState.Speaking
  val statusText = when (state) {
    AssistantState.Listening ->
      stringResource(R.string.listening) + " " + stringResource(R.string.release_to_send)
    AssistantState.Transcribing -> stringResource(R.string.transcribing)
    AssistantState.Thinking -> stringResource(R.string.thinking)
    AssistantState.Responding -> stringResource(R.string.responding)
    AssistantState.Idle, AssistantState.Speaking -> ""
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    tonalElevation = 2.dp,
  ) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
      if (statusText.isNotEmpty()) {
        Text(
          text = statusText,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BasicTextField(
          value = value,
          onValueChange = onValueChange,
          enabled = canType,
          textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
          ),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { onSend() }),
          maxLines = 4,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
          decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
              if (value.isEmpty()) {
                Text(
                  text = stringResource(R.string.practice_message_hint),
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              innerTextField()
            }
          },
        )
        if (value.isNotBlank() && canType) {
          FilledIconButton(
            onClick = onSend,
            modifier = Modifier.size(48.dp),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.Send,
              contentDescription = stringResource(R.string.send),
            )
          }
        } else {
          MicButton(
            state = state,
            onPressStart = onMicPressStart,
            onPressEnd = onMicPressEnd,
            onPressCancel = onMicPressCancel,
          )
        }
      }
    }
  }
}

/** Hold-to-talk button with a pulse while listening and a spinner while working. */
@Composable
fun MicButton(
  state: AssistantState,
  onPressStart: () -> Unit,
  onPressEnd: () -> Unit,
  onPressCancel: () -> Unit,
  contentDescription: String = "Tap and hold to talk",
) {
  val haptics = LocalHapticFeedback.current
  val currentState by rememberUpdatedState(state)
  val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
  val pulse by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.08f,
    animationSpec = infiniteRepeatable(
      animation = tween(650),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "micPulseScale",
  )
  val containerColor = when (state) {
    AssistantState.Idle, AssistantState.Listening -> MaterialTheme.colorScheme.primary
    AssistantState.Speaking -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.surface
  }
  val contentColor = when (state) {
    AssistantState.Idle, AssistantState.Listening -> MaterialTheme.colorScheme.onPrimary
    AssistantState.Speaking -> MaterialTheme.colorScheme.onTertiary
    else -> MaterialTheme.colorScheme.primary
  }

  Box(
    modifier = Modifier
      .size(48.dp)
      .scale(if (state == AssistantState.Listening) pulse else 1f)
      .clip(CircleShape)
      .background(containerColor)
      .pointerInput(Unit) {
        detectTapGestures(
          onPress = {
            if (
              currentState == AssistantState.Idle ||
                currentState == AssistantState.Speaking
            ) {
              haptics.performHapticFeedback(HapticFeedbackType.LongPress)
              onPressStart()
              val released = tryAwaitRelease()
              if (released) onPressEnd() else onPressCancel()
            }
          },
        )
      }
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    when (state) {
      AssistantState.Transcribing,
      AssistantState.Thinking,
      AssistantState.Responding -> {
        CircularProgressIndicator(
          color = contentColor,
          modifier = Modifier.size(22.dp),
          strokeWidth = 2.dp,
        )
      }
      AssistantState.Speaking -> {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(22.dp),
        )
      }
      else -> {
        Icon(
          imageVector = Icons.Rounded.Mic,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(22.dp),
        )
      }
    }
  }
}

/** Centered orb + title + hint for an empty surface. */
@Composable
fun EmptyOrb(modifier: Modifier = Modifier, title: String, hint: String) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Box(
        modifier = Modifier
          .size(104.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Mic,
          contentDescription = null,
          modifier = Modifier.size(42.dp),
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Spacer(Modifier.height(22.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
      )
      Spacer(Modifier.height(8.dp))
      Text(
        text = hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
