package com.typedefai.lingua.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typedefai.lingua.AppViewModel
import com.typedefai.lingua.R
import com.typedefai.lingua.dict.DictEntry

/**
 * Dictionary tab: word search backed by the bundled ECDICT subset, with suggestions for misses,
 * Chinese-meaning search, pronunciation, wordbook saving, and an AI explanation.
 */
@Composable
fun DictionaryScreen(viewModel: AppViewModel) {
  val dictionary by viewModel.dictionary.collectAsStateWithLifecycle()
  var query by rememberSaveable { mutableStateOf("") }
  val focusManager = LocalFocusManager.current

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.weight(1f),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text(stringResource(R.string.dict_search_hint)) },
        leadingIcon = {
          Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
          )
        },
        trailingIcon = {
          if (query.isNotEmpty()) {
            IconButton(onClick = { query = "" }) {
              Icon(
                imageVector = Icons.Rounded.Clear,
                contentDescription = stringResource(R.string.translate_clear),
              )
            }
          }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          onSearch = {
            focusManager.clearFocus()
            viewModel.lookupWord(query)
          },
        ),
      )
      Spacer(Modifier.width(8.dp))
      Button(
        onClick = {
          focusManager.clearFocus()
          viewModel.lookupWord(query)
        },
        enabled = query.isNotBlank(),
      ) {
        Text(stringResource(R.string.dict_search_button))
      }
    }

    val entry = dictionary.entry
    when {
      entry != null -> EntryDetail(
        entry = entry,
        isSaved = dictionary.isSaved,
        explanation = dictionary.explanation,
        explaining = dictionary.explaining,
        onSpeak = { viewModel.speakWord(entry.word) },
        onSave = viewModel::saveCurrentWord,
        onExplain = viewModel::explainWord,
      )
      dictionary.searched -> MissResults(
        suggestions = dictionary.suggestions,
        chineseResults = dictionary.chineseResults,
        note = dictionary.note,
        onPick = { word ->
          query = word
          viewModel.lookupWord(word)
        },
        onSpeak = viewModel::speakWord,
      )
      else -> Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = stringResource(R.string.dict_title),
          style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.dict_empty_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun EntryDetail(
  entry: DictEntry,
  isSaved: Boolean,
  explanation: String,
  explaining: Boolean,
  onSpeak: () -> Unit,
  onSave: () -> Unit,
  onExplain: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = entry.word,
          style = MaterialTheme.typography.headlineMedium,
        )
        if (entry.phonetic.isNotBlank()) {
          Text(
            text = "/${entry.phonetic}/",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      IconButton(onClick = onSpeak) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
          contentDescription = stringResource(R.string.dict_speak),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      IconButton(onClick = onSave) {
        Icon(
          imageVector = if (isSaved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
          contentDescription = stringResource(
            if (isSaved) R.string.dict_saved else R.string.dict_save,
          ),
          tint = if (isSaved) {
            MaterialTheme.colorScheme.tertiary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
    }

    // Exam/level tags, e.g. CET4, IELTS.
    val tags = entry.examTags { key ->
      when (key) {
        "zk" -> stringResource(R.string.tag_zk)
        "gk" -> stringResource(R.string.tag_gk)
        "cet4" -> stringResource(R.string.tag_cet4)
        "cet6" -> stringResource(R.string.tag_cet6)
        "ky" -> stringResource(R.string.tag_ky)
        "toefl" -> stringResource(R.string.tag_toefl)
        "ielts" -> stringResource(R.string.tag_ielts)
        "gre" -> stringResource(R.string.tag_gre)
        else -> null
      }
    }
    val posTags = entry.pos.split(Regex("[/\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
    if (posTags.isNotEmpty() || tags.isNotEmpty()) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        posTags.take(6).forEach { pos ->
          SuggestionChip(
            onClick = {},
            label = { Text(pos, style = MaterialTheme.typography.labelSmall) },
          )
        }
        tags.forEach { tag ->
          SuggestionChip(
            onClick = {},
            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
          )
        }
      }
    }

    // Inflected forms.
    val forms = entry.exchangeForms { key ->
      when (key) {
        "p" -> stringResource(R.string.exchange_p)
        "d" -> stringResource(R.string.exchange_d)
        "i" -> stringResource(R.string.exchange_i)
        "3" -> stringResource(R.string.exchange_3)
        "r" -> stringResource(R.string.exchange_r)
        "t" -> stringResource(R.string.exchange_t)
        "s" -> stringResource(R.string.exchange_s)
        "0" -> stringResource(R.string.exchange_0)
        "1" -> stringResource(R.string.exchange_1)
        else -> null
      }
    }
    if (forms.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        forms.forEach { (form, label) ->
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = form,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = label,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        SelectionContainer {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entry.translationLines.forEach { line ->
              Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
              )
            }
          }
        }
      }
    }

    if (entry.definition.isNotBlank()) {
      Column {
        Text(
          text = stringResource(R.string.dict_definition_title),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
          Text(
            text = entry.definition,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }

    if (explanation.isBlank() && !explaining) {
      OutlinedButton(
        onClick = onExplain,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(
          imageVector = Icons.Rounded.AutoAwesome,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.dict_ai_explain))
      }
    }

    if (explaining || explanation.isNotBlank()) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text(
            text = stringResource(R.string.dict_ai_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
          )
          SelectionContainer {
            Text(
              text = explanation.ifBlank { stringResource(R.string.thinking) },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
        }
      }
    }

    Spacer(Modifier.height(8.dp))
  }
}

@Composable
private fun MissResults(
  suggestions: List<DictEntry>,
  chineseResults: List<DictEntry>,
  note: String?,
  onPick: (String) -> Unit,
  onSpeak: (String) -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(
      horizontal = 16.dp,
      vertical = 8.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (suggestions.isNotEmpty()) {
      item {
        Text(
          text = stringResource(R.string.dict_suggestions_title),
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(vertical = 4.dp),
        )
      }
      items(suggestions, key = { it.word }) { entry ->
        WordRow(entry = entry, onPick = onPick, onSpeak = onSpeak)
      }
    }
    if (chineseResults.isNotEmpty()) {
      item {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
          text = stringResource(R.string.dict_chinese_title),
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(vertical = 4.dp),
        )
      }
      items(chineseResults, key = { it.word }) { entry ->
        WordRow(entry = entry, onPick = onPick, onSpeak = onSpeak)
      }
    }
    if (note != null) {
      item {
        Text(
          text = note,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun WordRow(
  entry: DictEntry,
  onPick: (String) -> Unit,
  onSpeak: (String) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onPick(entry.word) }
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = entry.word,
          style = MaterialTheme.typography.titleSmall,
        )
        if (entry.phonetic.isNotBlank()) {
          Spacer(Modifier.width(8.dp))
          Text(
            text = "/${entry.phonetic}/",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Text(
        text = entry.translationLines.firstOrNull().orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconButton(onClick = { onSpeak(entry.word) }) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
        contentDescription = stringResource(R.string.dict_speak),
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
