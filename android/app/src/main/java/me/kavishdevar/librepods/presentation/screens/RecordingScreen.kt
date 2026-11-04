package me.kavishdevar.librepods.presentation.screens

import android.content.ContentUris
import android.content.Intent
import android.provider.MediaStore
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.database.getStringOrNull
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.ListItemOrientation
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import java.util.Date

@Composable
fun RecordingScreenRoute(viewModel: AirPodsViewModel, onOpenTelegramCalls: () -> Unit = {}) {
    val recordingState by viewModel.hiResRecordingState.collectAsState()
    RecordingScreen(
        recording = recordingState.recording,
        message = recordingState.message,
        level = recordingState.level,
        onToggleRecording = viewModel::toggleHiResRecording,
        onOpenTelegramCalls = onOpenTelegramCalls
    )
}

private data class SavedRecording(
    val uri: android.net.Uri,
    val name: String,
    val dateAdded: Long
)

@Composable
private fun RecordingScreen(
    recording: Boolean,
    message: String?,
    level: Float,
    onToggleRecording: () -> Unit,
    onOpenTelegramCalls: () -> Unit
) {
    val context = LocalContext.current
    var recordings by remember { mutableStateOf(emptyList<SavedRecording>()) }
    val levelHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(recording, message) {
        recordings = withContext(Dispatchers.IO) { loadSavedRecordings(context) }
    }
    LaunchedEffect(recording, level) {
        if (!recording) {
            levelHistory.clear()
        } else {
            levelHistory.add(level)
            while (levelHistory.size > 240) levelHistory.removeAt(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            targetState = recording,
            label = "recording-content",
            modifier = Modifier.weight(1f)
        ) { isRecording ->
            if (isRecording) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                        .padding(vertical = 24.dp)
                ) {
                    if (levelHistory.isEmpty()) return@Canvas
                    val spacing = 2.dp.toPx()
                    val barWidth = 3.dp.toPx()
                    val visibleCount = ((size.width + spacing) / (barWidth + spacing)).toInt()
                    val start = (levelHistory.size - visibleCount).coerceAtLeast(0)
                    val centerY = size.height / 2f
                    var x = size.width - barWidth

                    for (index in levelHistory.lastIndex downTo start) {
                        val height = 6.dp.toPx() + levelHistory[index].coerceIn(0f, 1f) * size.height * 0.45f
                        drawRoundRect(
                            color = Color(0xFFFF4D4D),
                            topLeft = Offset(x, centerY - height),
                            size = Size(barWidth, height * 2),
                            cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
                        )
                        x -= barWidth + spacing
                        if (x < 0f) break
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.recorder_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StyledListItem(
                        name = stringResource(R.string.telegram_calls),
                        description = stringResource(R.string.telegram_calls_description),
                        orientation = ListItemOrientation.Vertical,
                        onClick = onOpenTelegramCalls
                    )
                    message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (recordings.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_recordings),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        StyledList(title = stringResource(R.string.recordings)) {
                            recordings.forEach { item ->
                                val formattedDate = DateFormat.getDateFormat(context).format(Date(item.dateAdded)) +
                                    " · " + DateFormat.getTimeFormat(context).format(Date(item.dateAdded))
                                StyledListItem(
                                    name = item.name,
                                    description = formattedDate,
                                    orientation = ListItemOrientation.Vertical,
                                    onClick = {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(item.uri, "audio/wav")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, item.name))
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "No app can play WAV recordings", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        StyledButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onToggleRecording,
            backdrop = rememberLayerBackdrop(),
            surfaceColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        ) {
            Text(
                text = stringResource(if (recording) R.string.stop_recording else R.string.start_recording),
                style = MaterialTheme.typography.labelMedium,
                color = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun loadSavedRecordings(context: android.content.Context): List<SavedRecording> {
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DATE_ADDED
    )
    val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf("${android.os.Environment.DIRECTORY_MUSIC}/LibrePods/")
    val result = mutableListOf<SavedRecording>()

    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        "${MediaStore.Audio.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        while (cursor.moveToNext()) {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idIndex))
            result += SavedRecording(
                uri = uri,
                name = cursor.getStringOrNull(nameIndex) ?: "AirPods recording.wav",
                dateAdded = cursor.getLong(dateIndex) * 1000L
            )
        }
    }
    return result
}
