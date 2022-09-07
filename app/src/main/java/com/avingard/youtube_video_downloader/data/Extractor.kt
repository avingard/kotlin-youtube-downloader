package com.avingard.youtube_video_downloader.data

import android.net.Uri
import com.avingard.youtube_video_downloader.application.BASE_YT_ADDRESS
import com.avingard.youtube_video_downloader.application.httpClient
import com.avingard.youtube_video_downloader.application.json
import com.avingard.youtube_video_downloader.group
import com.avingard.youtube_video_downloader.jsToJson
import com.avingard.youtube_video_downloader.updateQueryParameter
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.security.SecureRandom
import java.util.concurrent.Executors



private val extractorDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

val formatMap = mapOf(
    //Video with Audio
    37 to FormatDetails(videoQuality = 1080, format = "mp4", vCodec = VCodec.H264, aCodec = ACodec.AAC, audioBitrate = 192),
    46 to FormatDetails(videoQuality = 1080, format = "webm", vCodec = VCodec.VP8, aCodec = ACodec.VORBIS, audioBitrate = 192),

    // Dash MP4 Video
    160 to FormatDetails(144, format= "mp4",  vCodec = VCodec.H264),
    133 to FormatDetails(240, format= "mp4",  vCodec = VCodec.H264),
    134 to FormatDetails(360, format= "mp4",  vCodec = VCodec.H264),
    135 to FormatDetails(480, format= "mp4",  vCodec = VCodec.H264),
    136 to FormatDetails(720, format= "mp4",  vCodec = VCodec.H264),
    137 to FormatDetails(1080, format= "mp4",  vCodec = VCodec.H264),
    264 to FormatDetails(1440, format= "mp4",  vCodec = VCodec.H264),
    266 to FormatDetails(2160, format= "mp4",  vCodec = VCodec.H264),
    298 to FormatDetails(1080, format= "mp4",  vCodec = VCodec.H264, fps = 60),
    299 to FormatDetails(2160, format= "mp4",  vCodec = VCodec.H264, fps = 60),

    //Dash MP4 Audio
    139 to FormatDetails(audioBitrate = 48, format = "m4a", aCodec = ACodec.AAC),
    140 to FormatDetails(audioBitrate = 128, format = "m4a", aCodec = ACodec.AAC),
    141 to FormatDetails(audioBitrate = 256, format = "m4a", aCodec = ACodec.AAC),
    256 to FormatDetails(audioBitrate = 192, format = "m4a", aCodec = ACodec.AAC),
    258 to FormatDetails(audioBitrate = 384, format = "m4a", aCodec = ACodec.AAC),


    // Dash WEBM Audio
    171 to FormatDetails(audioBitrate = 128, format ="webm", aCodec = ACodec.VORBIS),
    172 to FormatDetails(audioBitrate = 256, format ="webm", aCodec = ACodec.VORBIS),
    249 to FormatDetails(audioBitrate = 50, format = "webm", aCodec = ACodec.OPUS),
    250 to FormatDetails(audioBitrate = 70, format = "webm", aCodec = ACodec.OPUS),
    251 to FormatDetails(audioBitrate = 160, format = "webm", aCodec = ACodec.OPUS),


    // Dash WEBM VIDEO
    278 to FormatDetails(144, format = "webm", vCodec = VCodec.VP9),
    242 to FormatDetails(240, format = "webm", vCodec = VCodec.VP9),
    243 to FormatDetails(360, format = "webm", vCodec = VCodec.VP9),
    244 to FormatDetails(480, format = "webm", vCodec = VCodec.VP9),
    245 to FormatDetails(480, format = "webm", vCodec = VCodec.VP9),
    246 to FormatDetails(480, format = "webm", vCodec = VCodec.VP9),
    247 to FormatDetails(720, format = "webm", vCodec = VCodec.VP9),
    248 to FormatDetails(1080, format = "webm", vCodec = VCodec.VP9),
    271 to FormatDetails(1440, format = "webm", vCodec = VCodec.VP9),
    272 to FormatDetails(2160, format = "webm", vCodec = VCodec.VP9),
    302 to FormatDetails(720, format = "webm", vCodec = VCodec.VP9, fps = 60),
    303 to FormatDetails(1080, format = "webm", vCodec = VCodec.VP9, fps = 60),
    308 to FormatDetails(1440, format = "webm", vCodec = VCodec.VP9, fps = 60),
    313 to FormatDetails(2160, format = "webm", vCodec = VCodec.VP9),
    315 to FormatDetails(2160, format = "webm", vCodec = VCodec.VP9, fps = 60),
)


class Extractor {
    private lateinit var context: Context
    private lateinit var scope: ScriptableObject
    private val functionCache = mutableMapOf<String, Function>()

    suspend fun extract(url: String): Video {
        return withContext(extractorDispatcher) {
            try {
                context = Context.enter()
                scope = context.initSafeStandardObjects()
                context.optimizationLevel = -1;

                val videoHtmlPage = getPlayerHtmlPage(url)
                val playerResponseJson = extractPlayerResponseJson(Jsoup.parse(videoHtmlPage))
                val playerResponse = parsePlayerResponse(playerResponseJson)
                val playerJsFileUrl = extractPlayerJsFileUrl(videoHtmlPage)
                val playerJsFile = getPlayerJsFile(playerJsFileUrl)
                val playerId = playerJsFileUrl.split("/")[2]

                val formats = playerResponse.streamingData.adaptiveFormats
                    .filter {
                        formatMap.containsKey(it.itag)
                    }.map {
                        val itag = it.itag
                        val uri = if (it.url != null) Uri.parse(Uri.decode(it.url)) else buildUriFromSignatureCipher(it.signatureCipher!!, playerJsFile, playerId)
                        val nSig = uri.getQueryParameter("n")
                        val formatUrl = (if (nSig != null)  uri.updateQueryParameter("n", decodeNSignature(nSig, playerJsFile, playerId)) else  uri).toString()

                        VideoFormat(
                            url = formatUrl,
                            details = formatMap[itag]!!.copy(itag = itag),
                            contentLength = it.contentLength!!.toLong()
                        )
                    }

                Video(playerResponse.videoDetails, formats)
            } catch (e: Exception) {
                throw RuntimeException(e)
            } finally {
                context.close()
            }
        }
    }


    private suspend fun getPlayerHtmlPage(url: String): String = httpClient.get(url).body()

    fun parsePlayerResponse(jsonString: String): PlayerResponse = json.decodeFromString(jsonString)


    private fun extractPlayerResponseJson(playerDocument: Document): String {
        val scripts: Elements = playerDocument.getElementsByTag("script")
        val script = scripts.first { it.data().contains("streamingData") }.html()

        val payloadStart = script.indexOfFirst { it == '{' }
        val payloadEnd = script.indexOfLast { it == '}' }
        return script.substring(payloadStart, payloadEnd + 1)
    }


    private suspend fun getPlayerJsFile(playerJsFileUrl: String): String {
        return httpClient.get("$BASE_YT_ADDRESS$playerJsFileUrl").body()
    }


    private fun extractPlayerJsFileUrl(videoHtmlPage: String): String {
        val regex = Regex("/s/player/([^\"]+?).js")
        val match = regex.find(videoHtmlPage)

        return match?.value ?: throw RuntimeException("Couldn't extract playerJs url")
    }


    private fun buildUriFromSignatureCipher(signatureCipher: String, playerJsFile: String, playerId: String): Uri {
        val parts = signatureCipher.split("&").associateBy({ it.split("=")[0] }, { it.split("=")[1] })

        val url = Uri.decode(parts["url"])
        val sp = Uri.decode(parts["sp"])
        val s = Uri.decode(parts["s"])

        val functionId = "signatureCipher$playerId"
        val decoderFunction = functionCache.getOrPut(functionId) { getSignatureCipherDecoderFunction(playerJsFile) }
        val result = decoderFunction.call(context, scope, scope, arrayOf(s)) as String

        return Uri.parse(url).buildUpon().appendQueryParameter(sp, result).build()
    }


    private fun getSignatureCipherDecoderFunction(playerJsFile: String): Function {
        val decoderFunction = extractSignatureCipherDecoderFunction(playerJsFile)

        context.evaluateString(scope, decoderFunction.decoderScript, "signature cipher script", 1, null)
        scope.get(decoderFunction.decoderFunctionName, scope) as Function

        return scope.get(decoderFunction.decoderFunctionName, scope) as Function
    }


    private fun extractSignatureCipherDecoderFunction(playerJsFile: String): DecoderFunction {
        val decoderFunctionRegex = Regex("([a-zA-Z\\d\$]{1,4})=function\\(a\\)\\{a=a.split\\(\"\"\\);([A-za-z\\d_$]+)\\..*")
        val decoderFunctionMatch = decoderFunctionRegex.find(playerJsFile)?.value ?: throw RuntimeException("Couldn't extract signatureCipher decoder function")
        val decoderFunctionName = decoderFunctionMatch.substringBefore("=")
        val decoderFunction = "var $decoderFunctionMatch"

        val variableFunctionNameRegex = Regex("([a-zA-Z$][a-zA-Z0-9$]{0,2})\\.([a-zA-Z$][a-zA-Z0-9$]{0,2})\\(")
        val variableFunctionNameMatch = variableFunctionNameRegex.find(decoderFunction)?.value ?: throw RuntimeException("Couldn't extract signatureCipher variable function name")
        val variableFunctionName = variableFunctionNameMatch.split(".")[0]
        val variableFunction = extractVariableFunction(playerJsFile, variableFunctionName)

        return DecoderFunction(decoderFunctionName = decoderFunctionName, decoderScript = decoderFunction + variableFunction)
    }


    private fun extractVariableFunction(playerJsFile: String, variableFunctionName: String): String {
        var variableFunction = "var $variableFunctionName={"
        val startIndex = playerJsFile.indexOf(variableFunction) + variableFunction.length

        var braces = 1
        for (i in startIndex until playerJsFile.length) {
            if (braces == 0) {
                variableFunction += "${playerJsFile.substring(startIndex, i)};"
                break
            }

            if (playerJsFile[i] == '{') {
                braces++
            } else if (playerJsFile[i] == '}') {
                braces--
            }
        }
        return variableFunction
    }


    private fun decodeNSignature(nSig: String, playerJsFile: String, playerId: String): String {
        val functionId = "nSig$playerId"
        val decoderFunction = functionCache.getOrPut(functionId) { getNSigDecoderFunction(playerJsFile) }

        return decoderFunction.call(context, scope, scope, arrayOf(nSig)) as String
    }


    private fun getNSigDecoderFunction(playerJsFile: String): Function {
        val decoderFunction = extractNSigDecoderFunction(playerJsFile)

        context.evaluateString(scope, decoderFunction.decoderScript, "n signature script", 1, null)
        scope.get(decoderFunction.decoderFunctionName, scope) as Function

        return scope.get(decoderFunction.decoderFunctionName, scope) as Function
    }


    private fun extractNSigDecoderFunction(playerJsFile: String): DecoderFunction {
        val funcNameRegex = Regex("\\.get\\(\"n\"\\)\\)&&\\([a-zA-Z$]=(?<nfunc>[a-zA-Z0-9$]+)(?:\\[(?<index>\\d+)])?\\([a-zA-Z0-9]\\)")
        val funcNameMatch = funcNameRegex.find(playerJsFile) ?: throw RuntimeException("Couldn't extract nSig function")
        val name = funcNameMatch.group("nfunc")
        val idx = funcNameMatch.group("index")

        val decoderNameRegex = Regex("var ${Regex.escape(name)}\\s*=\\s*(\\[.+?]);")
        val decoderNameMatch = decoderNameRegex.find(playerJsFile) ?: throw RuntimeException("Couldn't extract nSig decoder name")
        val json = jsToJson(decoderNameMatch.destructured.component1())
        val list = Json.decodeFromString<List<String>>(json)
        val decoderFuncName = list[idx.toInt()]

        val decoderFuncRegex = Regex("(?s)${Regex.escape(decoderFuncName)}=function(.*?)return [a-zA-Z]\\.join\\(\"\"\\)")
        val decoderFuncMatch = decoderFuncRegex.find(playerJsFile)?.value ?: throw RuntimeException("Couldn't extract nSig decoder function")

        return DecoderFunction(decoderFunctionName = decoderFuncName, decoderScript = "var $decoderFuncMatch};")
    }
}


data class DecoderFunction(
    val decoderScript: String,
    val decoderFunctionName: String
)