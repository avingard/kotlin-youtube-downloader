package com.avingard.youtube_video_downloader.application

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import kotlinx.serialization.json.Json

const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.5060.134 Safari/537.36"
const val BASE_YT_ADDRESS = "https://www.youtube.com"

val httpClient = HttpClient(OkHttp) {
    install(HttpTimeout)
    install(UserAgent) {
        agent = USER_AGENT
    }

    engine {
        config {
            followRedirects(true)
        }
    }
}

val json = Json { ignoreUnknownKeys = true }
