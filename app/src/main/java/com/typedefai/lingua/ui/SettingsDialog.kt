package com.typedefai.lingua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.typedefai.lingua.R
import com.typedefai.lingua.asr.AsrModel
import com.typedefai.lingua.settings.AppSettings
import com.typedefai.lingua.settings.AudioSettings
import com.typedefai.lingua.settings.LlmSettings
import com.typedefai.lingua.settings.SettingsRanges
import com.typedefai.lingua.tts.TtsModel
import java.io.File

private enum class SettingsTab(val labelRes: Int) {
  MODEL(R.string.settings_tab_model),
  AUDIO(R.string.settings_tab_audio),
}

/**
 * Full-screen settings sheet modelled on Gallery's `ConfigDialog`: header, tabs, slider/selector
 * rows, and a footer with restore/cancel/save. Saving hands the new [AppSettings] back to the
 * ViewModel, which reloads the engines.
 */
@Composable
fun SettingsDialog(
  initial: AppSettings,
  onDismiss: () -> Unit,
  onSave: (AppSettings) -> Unit,
  showAudio: Boolean = true,
) {
  var llm by remember { mutableStateOf(initial.llm) }
  var audio by remember { mutableStateOf(initial.audio) }
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }
  val tabs = buildList {
    add(SettingsTab.MODEL)
    if (showAudio) add(SettingsTab.AUDIO)
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        SettingsHeader(onDismiss = onDismiss)

        TabRow(selectedTabIndex = selectedTab) {
          tabs.forEachIndexed { index, tab ->
            Tab(
              selected = selectedTab == index,
              onClick = { selectedTab = index },
              text = { Text(stringResource(tab.labelRes), maxLines = 1) },
            )
          }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
          when (tabs[selectedTab]) {
            SettingsTab.MODEL -> ModelConfigsTab(
              settings = llm,
              onChange = { llm = it },
            )
            SettingsTab.AUDIO -> AudioTab(
              settings = audio,
              onChange = { audio = it },
            )
          }
        }

        SettingsFooter(
          onRestoreDefaults = {
            when (tabs[selectedTab]) {
              SettingsTab.MODEL ->
                llm = llm.copy(
                  maxContextTokens = LlmSettings.DEFAULT.maxContextTokens,
                  maxOutputTokens = LlmSettings.DEFAULT.maxOutputTokens,
                  topK = LlmSettings.DEFAULT.topK,
                  topP = LlmSettings.DEFAULT.topP,
                  temperature = LlmSettings.DEFAULT.temperature,
                )
              SettingsTab.AUDIO -> audio = AudioSettings.DEFAULT
            }
          },
          onCancel = onDismiss,
          onSave = { onSave(AppSettings(llm = llm, audio = audio)) },
        )
      }
    }
  }
}

@Composable
private fun SettingsHeader(onDismiss: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(R.string.settings_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 4.dp),
      )
      Text(
        text = stringResource(R.string.settings_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onDismiss) {
      Icon(
        imageVector = Icons.Rounded.Close,
        contentDescription = stringResource(R.string.settings_cancel),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SettingsFooter(
  onRestoreDefaults: () -> Unit,
  onCancel: () -> Unit,
  onSave: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedButton(onClick = onRestoreDefaults) {
      Text(stringResource(R.string.settings_restore_default))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = onCancel) {
        Text(stringResource(R.string.settings_cancel))
      }
      Spacer(Modifier.width(8.dp))
      Button(onClick = onSave) {
        Text(stringResource(R.string.settings_save))
      }
    }
  }
}

@Composable
private fun ModelConfigsTab(settings: LlmSettings, onChange: (LlmSettings) -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    NumberSliderRow(
      label = stringResource(R.string.settings_max_context),
      value = settings.maxContextTokens.toFloat(),
      min = SettingsRanges.CONTEXT_MIN.toFloat(),
      max = SettingsRanges.CONTEXT_MAX.toFloat(),
      decimals = 0,
      onValueChange = { onChange(settings.copy(maxContextTokens = it.toInt())) },
    )
    NumberSliderRow(
      label = stringResource(R.string.settings_max_output),
      value = settings.maxOutputTokens.toFloat(),
      min = SettingsRanges.MAX_OUTPUT_MIN.toFloat(),
      max = SettingsRanges.MAX_OUTPUT_MAX.toFloat(),
      decimals = 0,
      onValueChange = { onChange(settings.copy(maxOutputTokens = it.toInt())) },
    )
    NumberSliderRow(
      label = stringResource(R.string.settings_top_k),
      value = settings.topK.toFloat(),
      min = SettingsRanges.TOP_K_MIN.toFloat(),
      max = SettingsRanges.TOP_K_MAX.toFloat(),
      decimals = 0,
      onValueChange = { onChange(settings.copy(topK = it.toInt())) },
    )
    NumberSliderRow(
      label = stringResource(R.string.settings_top_p),
      value = settings.topP,
      min = SettingsRanges.TOP_P_MIN,
      max = SettingsRanges.TOP_P_MAX,
      decimals = 2,
      onValueChange = { onChange(settings.copy(topP = it)) },
    )
    NumberSliderRow(
      label = stringResource(R.string.settings_temperature),
      value = settings.temperature,
      min = SettingsRanges.TEMPERATURE_MIN,
      max = SettingsRanges.TEMPERATURE_MAX,
      decimals = 2,
      onValueChange = { onChange(settings.copy(temperature = it)) },
    )
    Text(
      text = stringResource(R.string.settings_llm_note),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun AudioTab(settings: AudioSettings, onChange: (AudioSettings) -> Unit) {
  val context = LocalContext.current
  val isAvailable: (String) -> Boolean = remember(context) {
    { fileName ->
      fileName.isBlank() || listOfNotNull(
        context.getExternalFilesDir(null)?.resolve("models"),
        File(context.filesDir, "models"),
      ).any { File(it, fileName).isFile }
    }
  }
  val asrModels = remember { AsrModel.entries }
  val ttsModels = remember { TtsModel.entries }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      text = stringResource(R.string.settings_asr_section),
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    SelectorRow(
      label = stringResource(R.string.settings_asr_model),
      description = stringResource(R.string.settings_asr_description),
      selectedLabel = settings.asrModel.title,
      selectedDetail = settings.asrModel.detail,
      items = asrModels,
      itemLabel = { it.title },
      itemDetail = { it.detail },
      itemEnabled = { isAvailable(it.fileName) },
      itemAvailable = { isAvailable(it.fileName) },
      onSelect = { onChange(settings.copy(asrModel = it)) },
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

    Text(
      text = stringResource(R.string.settings_tts_section),
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    SelectorRow(
      label = stringResource(R.string.settings_tts_model),
      description = stringResource(R.string.settings_tts_description),
      selectedLabel = settings.ttsModel.title,
      selectedDetail = settings.ttsModel.detail,
      items = ttsModels,
      itemLabel = { it.title },
      itemDetail = { it.detail },
      itemEnabled = { isAvailable(it.fileName) },
      itemAvailable = { isAvailable(it.fileName) },
      onSelect = { onChange(settings.copy(ttsModel = it, ttsVoiceId = "")) },
    )

    val voices = settings.ttsModel.voiceIds
    if (voices.isNotEmpty()) {
      SelectorRow(
        label = stringResource(R.string.settings_tts_voice),
        description = stringResource(R.string.settings_tts_voice_description),
        selectedLabel = settings.ttsVoiceId.ifBlank { settings.ttsModel.voiceId },
        selectedDetail = null,
        items = voices,
        itemLabel = { it },
        itemDetail = { null },
        itemEnabled = { true },
        itemAvailable = { true },
        onSelect = { onChange(settings.copy(ttsVoiceId = it)) },
      )
    }
  }
}

/** Slider + numeric field, matching Gallery's `NumberSliderRow`. */
@Composable
private fun NumberSliderRow(
  label: String,
  value: Float,
  min: Float,
  max: Float,
  decimals: Int,
  onValueChange: (Float) -> Unit,
) {
  val focusManager = LocalFocusManager.current
  var textValue by remember { mutableStateOf(formatValue(value, decimals)) }
  var isFocused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(value, isFocused) {
    if (!isFocused) {
      textValue = formatValue(value, decimals)
    }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = "$label (${formatValue(min, decimals)}-${formatValue(max, decimals)})",
      style = MaterialTheme.typography.titleSmall,
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Slider(
        modifier = Modifier.height(24.dp).weight(1f).padding(end = 8.dp),
        value = value,
        valueRange = min..max,
        onValueChange = {
          onValueChange(it)
          textValue = formatValue(it, decimals)
        },
      )
      Spacer(Modifier.width(8.dp))
      BasicTextField(
        value = textValue,
        modifier = Modifier
          .width(80.dp)
          .focusRequester(focusRequester)
          .onFocusChanged {
            isFocused = it.isFocused
            if (!isFocused) {
              textValue = formatValue(value, decimals)
            }
          },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        singleLine = true,
        onValueChange = { input ->
          textValue = input
          input.toFloatOrNull()?.let { parsed ->
            onValueChange(parsed.coerceIn(min, max))
          }
        },
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurface),
      ) { innerTextField ->
        Box(
          modifier = Modifier.border(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.outline
            },
            shape = RoundedCornerShape(4.dp),
          ),
        ) {
          Box(modifier = Modifier.padding(8.dp)) { innerTextField() }
        }
      }
    }
  }
}

/** Dropdown selector with availability subtitles, matching Gallery's audio model pickers. */
@Composable
private fun <T> SelectorRow(
  label: String,
  description: String,
  selectedLabel: String,
  selectedDetail: String?,
  items: List<T>,
  itemLabel: (T) -> String,
  itemDetail: (T) -> String?,
  itemEnabled: (T) -> Boolean,
  itemAvailable: (T) -> Boolean,
  onSelect: (T) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Text(
      description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .clickable { expanded = !expanded }
        .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(selectedLabel, style = MaterialTheme.typography.bodyMedium)
          if (!selectedDetail.isNullOrBlank()) {
            Text(
              selectedDetail,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Icon(
          imageVector = Icons.Rounded.ArrowDropDown,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.heightIn(max = 360.dp),
      ) {
        for (item in items) {
          val enabled = itemEnabled(item)
          DropdownMenuItem(
            text = {
              Column {
                Text(
                  itemLabel(item),
                  fontWeight = FontWeight.Normal,
                  color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                  },
                )
                itemDetail(item)?.let { detail ->
                  Text(
                    text = if (itemAvailable(item)) {
                      detail
                    } else {
                      "${stringResource(R.string.settings_not_downloaded)} · $detail"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            },
            enabled = enabled,
            onClick = {
              expanded = false
              onSelect(item)
            },
          )
        }
      }
    }
  }
}

private fun formatValue(value: Float, decimals: Int): String =
  if (decimals <= 0) value.toInt().toString() else "%.${decimals}f".format(value)
