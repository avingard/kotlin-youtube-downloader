package com.avingard.youtube_video_downloader

import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.avingard.youtube_video_downloader.data.*
import com.avingard.youtube_video_downloader.ui.theme.YoutubeVideoDownloaderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            YoutubeVideoDownloaderTheme(darkTheme = true) {
                SearchScreen()
            }
        }
    }
}


@Composable
fun SearchScreen(
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val workInfos by viewModel.getDownloadWorkInfosLiveData().observeAsState()
    val searchedVideo by viewModel.getSearchedVideo().collectAsState()

    val downloadInfo = remember(workInfos) {
        workInfos?.find { it.state == WorkInfo.State.RUNNING } ?: workInfos?.lastOrNull()
    }

    val enqueuedDownload = remember(downloadInfo) {
        viewModel.getEnqueuedDownload(downloadInfo?.progress)
    }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showDownloadInProgressDialog by remember { mutableStateOf(false) }
    var mediaUrl by remember { mutableStateOf(TextFieldValue()) }
    var loading by remember { mutableStateOf(false) }
    var switchQueuedDownload by remember { mutableStateOf<QueuedDownload?>(null) }

    if (showDownloadInProgressDialog) {
        enqueuedDownload?.let {
            DownloadInProgressDialog(
                download = enqueuedDownload,
                onDismiss = { showDownloadInProgressDialog = false },
                onConfirm = {
                    viewModel.cancelDownload()
                    showDownloadInProgressDialog = false
                    switchQueuedDownload?.let {
                        viewModel.download(it.video, it.selectedFormat)
                    }
                }
            )
        }
    }

    Surface {
        Box(modifier = Modifier
            .fillMaxSize()
        ) {

            Column(modifier = Modifier.align(Alignment.TopCenter)) {
                SearchBar(
                    query = mediaUrl,
                    onQueryChange = { mediaUrl = it },
                    onSearch = {
                        scope.launch {
                            focusManager.clearFocus()
                            loading = true

                            val uri = parseYoutubeUrl(mediaUrl.text)
                            if (uri == null) {
                                loading = false
                                Toast.makeText(context, "Wrong url", Toast.LENGTH_SHORT).show()
                                return@launch
                            }

                            viewModel.search(uri)
                            loading = false
                        }
                    },
                    modifier = Modifier.padding(20.dp)
                )

                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                AnimatedVisibility(visible = searchedVideo != null) {
                    searchedVideo?.let { video ->
                        ExtractedVideoCard(
                            video = video,
                            onDownload = { video, selectedFormat ->
                                if (enqueuedDownload == null || (downloadInfo != null && downloadInfo.state.isFinished)) {
                                    viewModel.download(video, selectedFormat)
                                } else if (downloadInfo?.state == WorkInfo.State.RUNNING) {
                                    switchQueuedDownload = QueuedDownload(video, selectedFormat)
                                    showDownloadInProgressDialog = true
                                }
                            }
                        )
                    }
                }
            }


            enqueuedDownload?.let {
                DownloadCard(
                    download = enqueuedDownload,
                    downloadInfo = downloadInfo!!,
                    modifier = Modifier
                        .padding(20.dp)
                        .align(Alignment.BottomCenter),
                    onCancelDownload = { viewModel.cancelDownload() },
                    onRetryDownload = { viewModel.download(it.video, it.selectedFormat) }
                )
            }

        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractedVideoCard(
    video: Video,
    onDownload: (Video, VideoFormat) -> Unit
) {
    val context = LocalContext.current
    var selectedFormat by remember {
        mutableStateOf(video.formats.filter { it.details.vCodec != VCodec.NONE }.maxBy { it.details.videoQuality!! })
    }

    val filteredFormats = remember {
        video.formats.filter { it.details.vCodec != VCodec.NONE }
    }

    ElevatedCard(modifier = Modifier.padding(20.dp)) {
        Column(modifier = Modifier
            .fillMaxWidth()
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data("https://img.youtube.com/vi/${video.details.videoId}/maxresdefault.jpg")
                        .crossfade(true)
                        .build(),
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                    placeholder = painterResource(R.drawable.placeholder)
                )
            }

            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
            ) {
                Text(
                    text = video.details.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    var expandMenu by remember { mutableStateOf(false) }

                    Box {
                        InputChip(
                            selected = false,
                            onClick = {
                                expandMenu = true
                            },
                            label = {
                                Text("${selectedFormat.details.videoQuality}p - ${selectedFormat.details.format} - ${Formatter.formatFileSize(context, selectedFormat.contentLength)}")
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null
                                )
                            }
                        )

                        DropdownMenu(
                            expanded = expandMenu,
                            onDismissRequest = {
                                expandMenu = false
                            }
                        ) {
                            filteredFormats.forEach {
                                DropdownMenuItem(
                                    text = {
                                        Text("${it.details.videoQuality}p - ${it.details.format} - ${Formatter.formatFileSize(context, it.contentLength)}")
                                    },
                                    onClick = {
                                        selectedFormat = it
                                        expandMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = {
                            onDownload(video, selectedFormat)
                        }
                    ) {
                        Text(text = "Download")
                    }
                }
            }
        }
    }
}


@Composable
fun DownloadCard(
    download: QueuedDownload,
    downloadInfo: WorkInfo,
    onCancelDownload: () -> Unit,
    onRetryDownload: (QueuedDownload) -> Unit,
    modifier: Modifier = Modifier
) {
    val downloadState = downloadInfo.state

    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = download.video.details.title,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(5.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(5f),
                    contentAlignment = Alignment.Center
                ) {

                    when(downloadState) {
                        WorkInfo.State.RUNNING -> {
                            val progress = downloadInfo.progress.getDouble(DownloadWorker.Progress, 0.0)

                            Column {
                                Text(text = "Downloading: ${(progress * 100).toInt()}%")
                                LinearProgressIndicator(progress.toFloat())
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            Text(text = "Download succeeded")
                        }
                        WorkInfo.State.FAILED -> {
                            Text(text = "Download failed")
                        }
                        WorkInfo.State.BLOCKED -> {
                            Text(text = "Download blocked")
                        }
                        WorkInfo.State.CANCELLED -> {
                            Text(text = "Download cancelled")
                        }
                        else -> {}
                    }
                }

                Box(
                    modifier = Modifier.weight(3f),
                    contentAlignment = Alignment.Center
                ) {
                    when(downloadState) {
                        WorkInfo.State.RUNNING -> {
                            Button(onClick = onCancelDownload) { Text("Cancel") }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            Button(onClick = { /*TODO*/ }) { Text("View") }
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, WorkInfo.State.BLOCKED -> {
                            Button(onClick = { onRetryDownload(download) }) { Text("Retry") }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}


@Composable
fun DownloadInProgressDialog(
    download: QueuedDownload,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val videoTitle = download.video.details.title

    AlertDialog(
        text = {
            Text("Another download ($videoTitle) process is already in progress. Would you like to cancel that and proceed with the new one?")
        },
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("No")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Yes")
            }
        }
    )

}


@Composable
fun SearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queryEmpty = query.text.isEmpty()

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { searchField ->
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(36.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (queryEmpty) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp)
                    ) {
                        Spacer(modifier = Modifier.width(15.dp))

                        Row(modifier = Modifier.weight(9.0f)) {
                            Text(
                                text = "Paste video url",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        IconButton(
                            onClick = { },
                            enabled = false
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(15.dp))

                    Row(modifier = Modifier.weight(9.0f)) {
                        searchField()
                    }

                    if (!queryEmpty) {
                        IconButton(onClick = { onQueryChange(query.copy(text = "")) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null
                            )
                        }
                    }
                }
            }
        }
    )
}
