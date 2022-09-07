package com.avingard.youtube_video_downloader

import android.net.Uri
import androidx.work.WorkInfo


fun MatchResult.group(name: String): String = groups[name]!!.value

fun Uri.updateQueryParameter(key: String, value: String): Uri {
    val builder = this.buildUpon().clearQuery()
    this.queryParameterNames.forEach {
        builder.appendQueryParameter(it, if (it != key) getQueryParameter(it) else value)
    }
    return builder.build()
}

fun parseYoutubeUrl(url: String): Uri? {
    if (url.contains("youtu.be") || url.contains("youtube.com")) {
        return Uri.parse(url)
    }
    return null
}


