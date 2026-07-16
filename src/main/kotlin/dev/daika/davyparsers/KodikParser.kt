package dev.daika.davyparsers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true }

/** A parser implementation for the Kodik player source. */
class KodikParser(client: OkHttpClient) : Parser {
    private val client: OkHttpClient = client.newBuilder().cookieJar(InMemoryCookieJar()).build()
    override suspend fun parse(iframeUrl: String, referer: String): PlayerData {
        val requestIframe = Request.Builder().url(iframeUrl).addHeader(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
            .addHeader(
                "Referer",
                referer
            )
            .build()
        val callIframe = client.newCall(requestIframe)

        var html: String
        var urlParams: Map<String, JsonElement>
        var hashContainer: KodikHashContainer
        callIframe.await().use { response ->
            if (!response.isSuccessful) {
                throw KodikApiException("Failed to fetch iframe content")
            }

            response.body.use { body ->
                html = body.string()
                urlParams = parseUrlParams(html)
                hashContainer = parseHashContainer(html)
            }
        }

        val doc = Jsoup.parse(html, iframeUrl)

        val skipTimes = parseSkipSegments(html)
        val translations = parseTranslationsList(doc)
        val populatedTranslations = mutableListOf<Translation>()
        val allSubtitles = mutableListOf<SubtitleTrack>()

        for (transInfo in translations) {
            val mediaHash = transInfo.hash ?: hashContainer.hash
            val mediaId = transInfo.mediaId ?: hashContainer.id

            try {
                val videoData = fetchVideoData(
                    targetUrl = "https://kodikplayer.com/ftor",
                    type = hashContainer.type,
                    hash = mediaHash,
                    id = mediaId,
                    urlParams = urlParams,
                    refererUrl = iframeUrl
                )

                populatedTranslations.add(
                    Translation(
                        id = transInfo.id,
                        name = transInfo.name,
                        streams = parseStreams(videoData)
                    )
                )

                if (allSubtitles.isEmpty()) {
                    allSubtitles.addAll(parseSubtitles(videoData))
                }
            } catch (e: KodikParserException) {
                println("Failed to load translation '${transInfo.name}': ${e.message}")
            } catch (e: Exception) {
                println("Failed to load translation '${transInfo.name}': Unexpected error: ${e.message}")
            }
        }

        return PlayerData(
            translations = populatedTranslations,
            skipTimes = skipTimes,
            subtitles = allSubtitles
        )
    }

    private suspend fun fetchVideoData(
        targetUrl: String,
        type: String,
        hash: String,
        id: String,
        urlParams: Map<String, JsonElement>,
        refererUrl: String
    ): KodikVideoResponseDto {
        val formBodyBuilder = FormBody.Builder()
            .add("hash", hash)
            .add("id", id)
            .add("type", type)
            .add("bad_user", "false")
            .add("cdn_is_working", "true")
            .add("info", "{}")

        val d = urlParams["d"]?.jsonPrimitive?.contentOrNull ?: ""
        val dSign = urlParams["d_sign"]?.jsonPrimitive?.contentOrNull ?: ""
        val pd = urlParams["pd"]?.jsonPrimitive?.contentOrNull ?: ""
        val pdSign = urlParams["pd_sign"]?.jsonPrimitive?.contentOrNull ?: ""
        val ref = URLDecoder.decode(urlParams["ref"]?.jsonPrimitive?.contentOrNull, "UTF-8") ?: ""
        val refSign = urlParams["ref_sign"]?.jsonPrimitive?.contentOrNull ?: ""

        formBodyBuilder.add("d", d)
        formBodyBuilder.add("d_sign", dSign)
        formBodyBuilder.add("pd", pd)
        formBodyBuilder.add("pd_sign", pdSign)
        formBodyBuilder.add(
            "ref",
            ref
        )
        formBodyBuilder.add("ref_sign", refSign)


        val request = Request.Builder()
            .url(targetUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", "https://kodikplayer.com")
            .header("Referer", refererUrl)
            .post(formBodyBuilder.build())
            .build()

        val responseBody = try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    throw KodikApiException("Kodik API request failed with HTTP ${response.code} at $targetUrl.")
                }

                response.body.use { body ->
                    body.string()
                        .also {
                            if (it.isBlank()) {
                                throw KodikApiException("Kodik API returned an empty response while fetching streams.")
                            }
                        }
                }
            }
        } catch (e: KodikParserException) {
            throw e
        } catch (e: Exception) {
            throw KodikApiException("Failed to call Kodik API endpoint: ${e.message ?: "unknown error"}")
        }

        val videoData = try {
            json.decodeFromString<KodikVideoResponseDto>(responseBody)
        } catch (e: Exception) {
            throw KodikParsingException("Failed to decode Kodik API response.", e)
        }

        videoData.error?.let { throw KodikApiException("Kodik API returned an error: $it") }
        return videoData
    }

    private fun parseStreams(videoData: KodikVideoResponseDto): List<MediaStream> {
        return videoData.links.mapNotNull { (quality, variants) ->
            val urls = variants.map { variant ->
                val decodedUrl = if ("mp4:hls:manifest" in variant.src) variant.src else decryptUrl(variant.src)
                if (decodedUrl.startsWith("//")) "https:$decodedUrl" else decodedUrl
            }

            if (urls.isEmpty()) null else MediaStream(urls = urls, quality = quality)
        }
    }

    private fun parseSubtitles(videoData: KodikVideoResponseDto): List<SubtitleTrack> {
        return videoData.subtitles.map { subObj ->
            SubtitleTrack(
                kind = "subtitles",
                label = subObj.language ?: "Unknown",
                language = subObj.language ?: "unk",
                src = subObj.src
            )
        }
    }

    private fun parseTranslationsList(document: Document): List<KodikTranslationInfo> {
        val options =
            document.select("div.serial-translations-box select option, div.movie-translations-box select option")
        return if (options.isNotEmpty()) {
            options.map { opt ->
                KodikTranslationInfo(
                    id = opt.attr("data-id").takeIf { it.isNotBlank() } ?: "0",
                    name = opt.text(),
                    mediaId = opt.attr("data-media-id").takeIf { it.isNotBlank() },
                    hash = opt.attr("data-media-hash").takeIf { it.isNotBlank() }
                )
            }
        } else {
            listOf(KodikTranslationInfo("0", "default", null, null))
        }
    }

    private fun parseUrlParams(html: String): Map<String, JsonElement> {
        val quotedRegex = Regex("""(?s)var\s+urlParams\s*=\s*['"](\{.*?\})['"]\s*;""")
        val quotedMatch = quotedRegex.find(html)
        if (quotedMatch != null) {
            return decodeUrlParams(quotedMatch.groupValues[1])
        }

        val unquotedRegex = Regex("""(?s)var\s+urlParams\s*=\s*(\{.*?\})\s*;""")
        val unquotedMatch = unquotedRegex.find(html)
        if (unquotedMatch != null) {
            return decodeUrlParams(unquotedMatch.groupValues[1])
        }

        return json.decodeFromString("{}")
    }

    private fun decodeUrlParams(rawJson: String): Map<String, JsonElement> {
        return try {
            json.decodeFromString(rawJson)
        } catch (e: Exception) {
            throw KodikParsingException("Failed to decode Kodik iframe urlParams JSON.", e)
        }
    }

    private fun parseHashContainer(html: String): KodikHashContainer {
        val typeMatch =
            Regex("""var\s*(?:type|video(?:Info)?\.type)\s*=\s*['"]([^'"]+)['"]""".trimMargin()).find(html)?.groupValues?.get(
                1
            ) ?: ""
        val hashMatch =
            Regex("""(?:hash|video(?:Info)?\.hash)\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        val idMatch =
            Regex("""(?:id|video(?:Info)?\.id)\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        return KodikHashContainer(typeMatch, hashMatch, idMatch)
    }

    private fun parseSkipSegments(html: String): List<TimeInterval> {
        val regex = Regex("""parseSkipButton\(\s*['"](.*?)['"]""")
        val match = regex.find(html) ?: return emptyList()

        val skipStr = match.groupValues[1]

        return skipStr.split(",").mapNotNull { segment ->
            val rangeStr = segment.replace(Regex("""\[.*?\]"""), "")
            val range = rangeStr.split("-")

            if (range.size == 2) {
                TimeInterval(start = timeToSeconds(range[0]), end = timeToSeconds(range[1]))
            } else {
                null
            }
        }
    }

    private fun timeToSeconds(timeStr: String): Long {
        val parts = timeStr.split(":").map { it.toLongOrNull() ?: 0L }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    private fun decryptUrl(crypted: String): String {
        for (rot in 0..25) {
            val shifted = crypted.map { shiftChar(it, rot) }.joinToString("")
            val padding = (4 - (shifted.length % 4)) % 4
            val padded = shifted + "=".repeat(padding)
            try {
                val result = String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
                if ("mp4:hls:manifest" in result) {
                    return result
                }
            } catch (_: IllegalArgumentException) {
                continue
            }
        }
        throw KodikDecryptionException("Failed to decrypt the stream URL.")
    }

    private fun shiftChar(c: Char, rot: Int): Char {
        if (!c.isLetter()) return c
        val isLower = c.isLowerCase()
        val alph = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val shiftedIndex = (alph.indexOf(c.uppercaseChar()) + rot) % alph.length
        val shiftedChar = alph[shiftedIndex]
        return if (isLower) shiftedChar.lowercaseChar() else shiftedChar
    }
}

private data class KodikTranslationInfo(
    val id: String,
    val name: String,
    val mediaId: String?,
    val hash: String?
)

private data class KodikHashContainer(
    val type: String,
    val hash: String,
    val id: String
)

@Serializable
private data class KodikVideoResponseDto(
    val links: Map<String, List<KodikVideoLinkDto>> = emptyMap(),
    val subtitles: List<KodikSubtitleDto> = emptyList(),
    val error: String? = null
)

@Serializable
private data class KodikVideoLinkDto(
    val src: String
)

@Serializable
private data class KodikSubtitleDto(
    val language: String? = null,
    val src: String
)

sealed class KodikParserException(message: String, cause: Throwable? = null) :
    ParserException(message, cause)

class KodikApiException(message: String) : KodikParserException(message)

class KodikParsingException(message: String, cause: Throwable? = null) :
    KodikParserException(message, cause)

class KodikDecryptionException(message: String) : KodikParserException(message)