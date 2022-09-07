package com.avingard.youtube_video_downloader.data

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class VideoRepository @Inject constructor(

) {
    private val videos = ConcurrentHashMap<String, Video>()

    fun addVideo(video: Video) {
        val videoId = video.details.videoId
        videos.putIfAbsent(videoId, video)
    }

    fun getVideo(videoId: String): Video? = videos[videoId]


}