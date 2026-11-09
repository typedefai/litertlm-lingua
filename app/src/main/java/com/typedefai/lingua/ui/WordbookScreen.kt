package com.typedefai.lingua.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typedefai.lingua.AppViewModel
import com.typedefai.lingua.R
import com.typedefai.lingua.dict.SavedWord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wordbook tab: words starred in the dictionary, most recently saved first. Tap a row to reopen
 * the entry; the speaker reads the word; the trash icon removes it.
 */
@Composable
fun WordbookScreen(viewModel: AppViewModel) {
  val savedWords by viewModel.savedWords.collectAsStateWithLifecycle()

  if (savedWords.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
          imageVector = Icons.Rounded.Bookmark,
          contentDescription = null,
          modifier = Modifier.height(48.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.wordbook_empty_title),
          style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.wordbook_empty_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    return
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Text(
      text = stringResource(R.string.wordbook_count, savedWords.size),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(
        horizontal = 16.dp,
        vertical = 4.dp,
      ),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(savedWords, key = { it.word }) { word ->
        WordbookRow(
          word = word,
          onOpen = { viewModel.openSavedWord(word.word) },
          onSpeak = { viewModel.speakWord(word.word) },
          onRemove = { viewModel.removeSavedWord(word.word) },
        )
      }
    }
  }
}

@Composable
private fun WordbookRow(
  word: SavedWord,
  onOpen: () -> Unit,
  onSpeak: () -> Unit,
  onRemove: () -> Unit,
) {
  val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Row(
      modifier = Modifier
        .clickable(onClick = onOpen)
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = word.word,
            style = MaterialTheme.typography.titleSmall,
          )
          if (word.phonetic.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
              text = "/${word.phonetic}/",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Text(
          text = word.translation.split('\n').firstOrNull().orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = dateFormat.format(Date(word.addedAt)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
      )
      IconButton(onClick = onSpeak) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
          contentDescription = stringResource(R.string.dict_speak),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      IconButton(onClick = onRemove) {
        Icon(
          imageVector = Icons.Rounded.DeleteOutline,
          contentDescription = stringResource(R.string.wordbook_remove),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
