package com.avingard.youtube_video_downloader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.avingard.youtube_video_downloader.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject


@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val workManager: WorkManager
): ViewModel() {
    private val searchedVideo = MutableStateFlow<Video?>(null)
    private val downloadRequestId = MutableStateFlow<UUID?>(null)

    private val extractor = Extractor()

    suspend fun search(uri: Uri) {
        searchedVideo.value = null
        val video = extractor.extract(uri.toString())
        repository.addVideo(video)
        this.searchedVideo.value = video
    }


    fun download(video: Video, selectedFormat: VideoFormat) {
        viewModelScope.launch {
            val workRequest = buildWorkRequest(video, selectedFormat)
            workManager.enqueueUniqueWork("download", ExistingWorkPolicy.REPLACE, workRequest)
            downloadRequestId.value = workRequest.id
        }
    }


    fun getEnqueuedDownload(data: Data?): QueuedDownload? {
        if (data == null) return null
        val videoId = data.getString(DownloadWorker.VideoID) ?: return null
        val selectedFormatId = data.getInt(DownloadWorker.SelectedVideoFormatID, 0)

        val video = repository.getVideo(videoId) ?: return null
        val selectedVideoFormat = video.formats.find { selectedFormatId == it.details.itag } ?: return null

        return QueuedDownload(video, selectedVideoFormat)
    }


    fun cancelDownload() {
        viewModelScope.launch {
            workManager.cancelUniqueWork("download")
        }
    }


    private fun buildWorkRequest(video: Video, selectedFormat: VideoFormat): OneTimeWorkRequest {
        val data = workDataOf(
            DownloadWorker.VideoID to video.details.videoId,
            DownloadWorker.SelectedVideoFormatID to selectedFormat.details.itag
        )

        return OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        NetworkType.CONNECTED
                    )
                    .build()
            )
            .setInputData(data)
            .build()
    }


    fun getDownloadWorkInfosLiveData() = workManager.getWorkInfosForUniqueWorkLiveData("download")

    fun getSearchedVideo(): StateFlow<Video?> = searchedVideo
}