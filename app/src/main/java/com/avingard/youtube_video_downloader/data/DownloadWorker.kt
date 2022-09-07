package com.avingard.youtube_video_downloader.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.EXTRA_NOTIFICATION_ID
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.avingard.youtube_video_downloader.R
import com.avingard.youtube_video_downloader.application.httpClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.Path
import kotlin.random.Random

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParameters: WorkerParameters,
    private val videoRepository: VideoRepository
): CoroutineWorker(context, workerParameters) {

    companion object {
        const val VideoID = "VideoID"
        const val SelectedVideoFormatID = "SelectedVideoFormatID"
        const val Progress = "Progress"
    }

    private val notificationId = Random.nextInt()
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val inputData = workerParameters.inputData
        val videoId = inputData.getString(VideoID) ?: return Result.failure()
        val selectedFormatId = inputData.getInt(SelectedVideoFormatID, 0)

        val video = videoRepository.getVideo(videoId) ?: return Result.failure()
        val selectedVideoFormat = video.formats.find { selectedFormatId == it.details.itag } ?: return Result.failure()

        val outputPath = getOutputPath(video, selectedVideoFormat)
        val downloaded = AtomicLong(0)
        val videoTitle = video.details.title

        suspend fun updateProgress(contentLen: Double, packetLen: Long) {
            val downloadedValue = downloaded.addAndGet(packetLen).toDouble()
            val progress = downloadedValue / contentLen

            setProgress(workDataOf(VideoID to videoId, Progress to progress, SelectedVideoFormatID to selectedFormatId))
            notificationManager.notify(notificationId, buildProgressNotification(videoTitle, (progress * 100).toInt()))
        }

        startForegroundService(videoTitle)
        setProgress(workDataOf(VideoID to videoId, Progress to 0.0, SelectedVideoFormatID to selectedFormatId))

        val outputFile = outputPath.toFile()
        if (selectedVideoFormat.hasAudio()) {
            val contentLen = selectedVideoFormat.contentLength.toDouble()

            return try {
                startRegularDownload(selectedVideoFormat, outputFile, onNewPacket = { updateProgress(contentLen, it) })
                Result.success()
            } catch (e: Exception) {
                e.printStackTrace()

                outputFile.delete()
                Result.failure()
            }
        }


        val audioFormat = video.getBestDashAudioFormat(selectedVideoFormat.details.format)
        val contentLen = (selectedVideoFormat.contentLength + audioFormat.contentLength).toDouble()
        val (tempVideoFile, tempAudioFile) = createTempFilesForMuxing(selectedVideoFormat)

        try {
            startDashDownload(selectedVideoFormat, audioFormat, tempVideoFile = tempVideoFile, tempAudioFile = tempAudioFile, onNewPacket = { updateProgress(contentLen, it) })
            val (returnCode, file) = mux(downloadedAudioFile = tempAudioFile, downloadedVideoFile = tempVideoFile, selectedFormat = selectedVideoFormat)

            return if (ReturnCode.isSuccess(returnCode)) {
                file.renameTo(outputFile)
                Result.success()
            } else {
                Result.failure()
            }

        } catch (e: Exception) {
            outputFile.delete()
            return Result.failure()
        } finally {
            tempVideoFile.delete()
            tempAudioFile.delete()
        }
    }


    private suspend fun startForegroundService(videoTitle: String) {
        setForeground(ForegroundInfo(notificationId, createNotificationBuilder(videoTitle).build()))
    }


    private fun buildProgressNotification(videoTitle: String, progress: Int): Notification {
        return createNotificationBuilder(videoTitle)
            .setProgress(100, progress, false)
            .build()
    }


    private fun createNotificationBuilder(videoTitle: String): NotificationCompat.Builder {
        val cancelIntent = Intent(applicationContext, DownloadBroadcastReceiver::class.java).apply {
            action = "DOWNLOAD_CANCEL"
            putExtra(EXTRA_NOTIFICATION_ID, 0)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(applicationContext, 0, cancelIntent, 0)

        return NotificationCompat.Builder(applicationContext, "download_channel")
            .setSmallIcon(R.drawable.ic_baseline_file_download_24)
            .setContentTitle(videoTitle)
            .addAction(R.drawable.ic_baseline_close_24, "CANCEL", cancelPendingIntent)
    }


    private suspend fun startRegularDownload(
        videoFormat: VideoFormat,
        outputFile: File,
        onNewPacket: suspend (packetSize: Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            download(videoFormat.url, outputFile, this, onNewPacket)
        }
    }


    private suspend fun startDashDownload(
        videoFormat: VideoFormat,
        audioFormat: VideoFormat,
        tempVideoFile: File,
        tempAudioFile: File,
        onNewPacket: suspend (packetSize: Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            coroutineScope {
                launch {
                    download(videoFormat.url, tempVideoFile, this, onNewPacket)
                }

                launch {
                    download(audioFormat.url, tempAudioFile, this, onNewPacket)
                }
            }
        }
    }


    private fun getOutputPath(video: Video, videoFormat: VideoFormat): Path {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
        return Path("$downloads/${video.details.title}.${videoFormat.details.format}")
    }


    private suspend fun download(
        url: String,
        outputFile: File,
        parentScope: CoroutineScope,
        onNewPacket: suspend (packetSize: Long) -> Unit
    ) {
        httpClient.prepareGet(url).execute { httpResponse ->
            val channel: ByteReadChannel = httpResponse.body()

            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong() * 1024)
                while (!packet.isEmpty) {
                    val bytes = packet.readBytes()
                    outputFile.appendBytes(bytes)

                    parentScope.launch { onNewPacket(bytes.size.toLong()) }
                }
            }
        }
    }


    private fun mux(
        downloadedVideoFile: File,
        downloadedAudioFile: File,
        selectedFormat: VideoFormat
    ): Pair<ReturnCode, File> {
        val format = selectedFormat.details.format
        val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
        val outputFilePath = "$outputDir/rapid_media${System.currentTimeMillis()}.$format"

        val session = FFmpegKit.execute("-i ${downloadedVideoFile.path} -i ${downloadedAudioFile.path} -map 0:v -map 1:a -c copy -shortest $outputFilePath")
        return session.returnCode to File(outputFilePath)
    }


    private fun createTempFilesForMuxing(videoFormat: VideoFormat): Pair<File, File> {
        val format = videoFormat.details.format
        return File.createTempFile("rapid_media", ".$format") to File.createTempFile("rapid_media", ".$format")
    }
}

class DownloadBroadcastReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val workManager = WorkManager.getInstance(context!!)
        workManager.cancelUniqueWork("download")
    }
}