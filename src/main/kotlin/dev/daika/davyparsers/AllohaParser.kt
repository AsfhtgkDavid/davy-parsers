package dev.daika.davyparsers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private const val userAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val acceptLanguage = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
private const val allohaOrigin = "https://alloha.yani.tv"
private const val allohaBnsiPath = "$allohaOrigin/bnsi/movies"
private val formMediaType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
private val fileListRegex =
    """const\s+fileList\s*=\s*JSON\.parse\(\s*(["'`])(.*?)\1\s*\)""".toRegex(RegexOption.DOT_MATCHES_ALL)

/** A parser implementation for the Alloha player source. */
class AllohaParser(private val client: OkHttpClient) : Parser {
    private val json = Json {
        ignoreUnknownKeys = true

    }

    override suspend fun parse(iframeUrl: String, referer: String): PlayerData {
        val iframeDocument = loadIframeDocument(iframeUrl, referer)
        val requestSeed = iframeDocument.selectFirst("meta[name=viewporti]")?.attr("content")
            ?: throw AllohaParsingException("Missing viewporti metadata required for request signing.")
        val token = extractToken(iframeUrl)
        val borth = buildBorthHeader(requestSeed)
        val activeId = extractActiveId(iframeDocument)
        val bnsiDto = fetchBnsiData(activeId, token, borth, iframeUrl)
        return mapPlayerData(bnsiDto)
    }

    override fun canParse(iframeUrl: String): Boolean {
        return iframeUrl.contains("alloha.yani.tv")
    }

    private suspend fun loadIframeDocument(iframeUrl: String, referer: String): Document {
        val request = Request.Builder().url(iframeUrl)
            .addHeader("Referer", referer)
            .addHeader("User-Agent", userAgent)
            .addHeader("Accept-Language", acceptLanguage)
            .build()

        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw AllohaApiException("Failed to fetch iframe content.")
            }

            response.body.use { body ->
                Jsoup.parse(body.byteStream(), "UTF-8", response.request.url.toString())
            }
        }
    }

    private fun extractToken(iframeUrl: String): String {
        return iframeUrl.toHttpUrlOrNull()?.queryParameter("token")
            ?: throw AllohaParsingException("Missing 'token' query parameter in iframe URL.")
    }

    private fun buildBorthHeader(requestSeed: String): String {
        return "a|" + decodeBorthSeed(requestSeed)
    }

    private suspend fun fetchBnsiData(
        activeId: Int,
        token: String,
        borth: String,
        iframeUrl: String
    ): AllohaBnsiDto {
        val request = Request.Builder().url("$allohaBnsiPath/$activeId")
            .post(buildBnsiRequestBody(token))
            .addHeader("User-Agent", userAgent)
            .addHeader("Borth", borth)
            .addHeader("Referer", iframeUrl)
            .addHeader("Origin", allohaOrigin)
            .addHeader("Accept-Language", acceptLanguage)
            .build()

        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw AllohaApiException("Alloha BNSI request failed with HTTP ${response.code}.")
            }

            response.body.use { body ->
                val rawBody = body.string()
                if (rawBody.isBlank()) {
                    throw AllohaApiException("Alloha BNSI response body is empty.")
                }

                try {
                    json.decodeFromString<AllohaBnsiDto>(rawBody)
                } catch (e: Exception) {
                    throw AllohaParsingException("Failed to decode Alloha BNSI response JSON.", e)
                }
            }
        }
    }

    private fun buildBnsiRequestBody(token: String) = FormBody.Builder()
        .add("token", token)
        .add("av1", "false")
        .add("autoplay", "0")
        .add("audio", "")
        .add("subtitle", "")
        .build()

    private fun mapPlayerData(bnsiDto: AllohaBnsiDto): PlayerData {
        return PlayerData(
            translations = bnsiDto.hlsSource.map { source ->
                Translation(
                    id = source.audioId,
                    name = source.label,
                    streams = source.quality.map { (quality, url) ->
                        MediaStream(urls = parseUrls(url), quality = quality)
                    }
                )
            },
            skipTimes = parseSkipTimes(bnsiDto.skipTime),
            subtitles = bnsiDto.tracks?.map { track ->
                SubtitleTrack(
                    kind = track.kind,
                    label = track.label,
                    language = track.language,
                    src = track.src,
                    isDefault = track.isDefault
                )
            } ?: emptyList()
        )
    }

    private fun parseUrls(urls: String): List<String> {
        return urls.split(" or ").map { it.trim() }
    }

    private fun parseSkipTimes(skipTime: String): List<TimeInterval> {
        return skipTime.split(",").mapNotNull { interval ->
            val parts = interval.split("-")
            if (parts.size != 2) {
                return@mapNotNull null
            }

            val start = parts[0].toLongOrNull()
            val end = parts[1].toLongOrNull()
            if (start == null || end == null) {
                null
            } else {
                TimeInterval(start, end)
            }
        }
    }

    private fun extractActiveId(document: Document): Int {
        val scriptContent = document.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("const fileList") }
            ?: throw AllohaParsingException("Failed to find active media id in Alloha script payload.")

        val jsonString = fileListRegex.find(scriptContent)?.groupValues?.get(2)
            ?: throw AllohaParsingException("Failed to extract JSON from Alloha script.")

        val cleanJson = jsonString
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        return try {
            json.decodeFromString<AllohaFileListDto>(cleanJson).active.id
        } catch (_: Exception) {
            throw AllohaParsingException("Failed to decode Alloha BNSI response JSON.")
        }
    }

    private fun decodeBorthSeed(seed: String): String {
        return unscramble(reorderByBitWidth(reorderByBitDepth(seed)))
    }

    private fun reorderByBitWidth(source: String): String {
        val length = source.length
        if (length <= 0) {
            return source
        }

        var u = 0
        while ((1 shl u) < length) {
            u++
        }

        fun bucketIndex(index: Int): Int {
            if (index == 0) {
                return u
            }

            var g = 0
            var value = index
            while ((1 and value) == 0) {
                g++
                value = value shr 1
            }
            return g
        }

        val aj = IntArray(u + 1)
        for (index in 0 until length) {
            aj[bucketIndex(index)]++
        }

        val buckets = Array(u + 1) { "" }
        var pos = 0
        for (i in 0..u) {
            buckets[i] = source.substring(pos, pos + aj[i])
            pos += aj[i]
        }

        val pointers = IntArray(u + 1)
        val res = CharArray(length)
        for (index in 0 until length) {
            val bucket = bucketIndex(index)
            res[index] = buckets[bucket][pointers[bucket]++]
        }

        return String(res)
    }

    private fun reorderByBitDepth(source: String): String {
        val length = source.length
        if (length <= 0) {
            return source
        }

        var u = 0
        while ((1 shl u) < length) {
            u++
        }

        fun bucketIndex(index: Int): Int {
            if (index == 0) {
                return 0
            }

            var d = 0
            var value = index
            while (value > 0) {
                d++
                value = value shr 1
            }
            return d
        }

        val aj = IntArray(u + 1)
        for (index in 0 until length) {
            aj[bucketIndex(index)]++
        }

        val buckets = Array(u + 1) { "" }
        var pos = 0
        for (i in u downTo 0) {
            buckets[i] = source.substring(pos, pos + aj[i])
            pos += aj[i]
        }

        val pointers = IntArray(u + 1)
        val res = CharArray(length)
        for (index in 0 until length) {
            val bucket = bucketIndex(index)
            res[index] = buckets[bucket][pointers[bucket]++]
        }

        return String(res)
    }

    private fun isPrime(n: Int): Boolean {
        if (n < 2) return false
        if (n % 2 == 0) return n == 2
        var i = 3
        while (i * i <= n) {
            if (n % i == 0) return false
            i += 2
        }
        return true
    }

    private fun nextPrimeAtOrAbove(value: Int): Int {
        var candidate = maxOf(1, value)
        while (!isPrime(candidate)) {
            candidate++
        }
        return candidate
    }

    private fun unscramble(source: String): String {
        val length = source.length
        if (length <= 1) {
            return source
        }

        val modulus = nextPrimeAtOrAbove(length + 1)

        val visited = BooleanArray(length)
        val indexes = IntArray(length)
        var filled = 0
        var current = 0

        while (filled < length) {
            current = (current + 2) % modulus
            if (current < length && !visited[current]) {
                indexes[filled++] = current
                visited[current] = true
            }
        }

        val decoded = CharArray(length)
        for (index in 0 until length) {
            decoded[indexes[index]] = source[index]
        }

        return String(decoded)
    }
}

@Serializable
private data class AllohaFileListDto(val active: AllohaFileListEntityDto)

@Serializable
private data class AllohaFileListEntityDto(val id: Int)

@Serializable
private data class AllohaBnsiDto(
    val skipTime: String,
    val hlsSource: List<AllohaHlsSourceDto>,
    val tracks: List<AllohaTrackDto>?
)

@Serializable
private data class AllohaHlsSourceDto(
    val label: String,
    val quality: Map<String, String>,
    val audioId: String
)

@Serializable
private data class AllohaTrackDto(
    val kind: String,
    val label: String,
    val src: String,
    val language: String,
    @SerialName("default")
    val isDefault: Boolean = false
)

sealed class AllohaParserException(message: String, cause: Throwable? = null) :
    ParserException(message, cause)

class AllohaApiException(message: String) : AllohaParserException(message)

class AllohaParsingException(message: String, cause: Throwable? = null) :
    AllohaParserException(message, cause)