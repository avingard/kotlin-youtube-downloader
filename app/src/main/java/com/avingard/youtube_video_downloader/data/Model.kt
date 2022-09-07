package com.avingard.youtube_video_downloader.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable


data class QueuedDownload(
    val video: Video,
    val selectedFormat: VideoFormat
)

enum class VCodec {
    H264, VP9, NONE, VP8
}

enum class ACodec {
    AAC, NONE, OPUS, VORBIS
}


@Parcelize
data class FormatDetails(
    val videoQuality: Int? = null,
    val audioBitrate: Int? = null,
    val format: String,
    val vCodec: VCodec = VCodec.NONE,
    val aCodec: ACodec = ACodec.NONE,
    val fps: Int? = null,
    val itag: Int = 0
) : Parcelable

@Parcelize
data class Video(
    val details: VideoDetails,
    val formats: List<VideoFormat>
) : Parcelable {

    fun getBestDashAudioFormat(format: String) = formats
        .filter { if (format == "webm") it.details.format == format else it.details.format == "m4a" }
        .filter { it.details.aCodec != ACodec.NONE && it.details.vCodec == VCodec.NONE }
        .filter { it.details.audioBitrate != null }
        .maxBy { it.details.audioBitrate!! }
}


@Parcelize
@Serializable
data class VideoDetails(
    val videoId: String,
    val title: String
) : Parcelable


@Parcelize
data class VideoFormat(
    val url: String,
    val details: FormatDetails,
    val contentLength: Long
) : Parcelable {

    fun hasAudio() = details.aCodec != ACodec.NONE
}


@Serializable
data class PlayerResponse(
    val videoDetails: VideoDetails,
    val streamingData: StreamingData
)

@Serializable
data class StreamingData(
    val formats: List<Format>,
    val adaptiveFormats: List<Format>
)


@Serializable
data class Format(
    val itag: Int,
    val contentLength: String? = null,
    val signatureCipher: String? = null,
    val url: String? = null
)