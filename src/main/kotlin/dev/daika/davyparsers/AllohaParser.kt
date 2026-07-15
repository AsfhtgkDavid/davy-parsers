package dev.daika.davyparsers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/** A parser implementation for the Alloha player source. */
class AllohaParser(private val client: OkHttpClient) : Parser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Loads playback metadata and converts it to [PlayerData]. */
    override suspend fun parse(iframeUrl: String, referer: String): PlayerData {
        val requestIframe = Request.Builder().url(iframeUrl)
            .addHeader("Referer", referer)
            .addHeader("User-Agent", userAgent)
            .addHeader("Accept-Language", acceptLanguage)
            .build()
        val callIframe = client.newCall(requestIframe)
        val doc = callIframe.await().use { response ->
            if (!response.isSuccessful) {
                throw AllohaApiException("Failed to fetch iframe content.")
            }
            response.body.use { body ->
                Jsoup.parse(body.byteStream(), "UTF-8", response.request.url.toString())
            }
        }
        val wl = doc.selectFirst("meta[name=viewporti]")?.attr("content")
            ?: throw AllohaParsingException("Missing viewport metadata required for request signing.")
        val token = iframeUrl.toHttpUrlOrNull()?.queryParameter("token")
            ?: throw AllohaParsingException("Missing 'token' query parameter in iframe URL.")
        val aj = a6(a7(wl))
        val borth = "a|" + unscramble(aj)

        val id = getActiveId(doc)
        val requestM3u8 = Request.Builder().url("https://alloha.yani.tv/bnsi/movies/$id")
            .post(
                "token=$token&av1=false&autoplay=0&audio=&subtitle="
                    .toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
            )
            .addHeader("User-Agent", userAgent)
            .addHeader("Borth", borth)
            .addHeader("Referer", iframeUrl)
            .addHeader("Origin", "https://alloha.yani.tv")
            .addHeader("Accept-Language", acceptLanguage)
            .build()
        val callM3u8 = client.newCall(requestM3u8)

        val bnsiDto = callM3u8.await().use { response ->
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

        return PlayerData(
            translations = bnsiDto.hlsSource.map { source ->
                Translation(
                    id = source.audioId,
                    name = source.label,
                    streams = source.quality.map { (quality, url) ->
                        MediaStream(urls = url.split(" or ").map { it.trim() }, quality = quality)
                    }
                )
            },
            skipTimes = bnsiDto.skipTime.split(",").mapNotNull { interval ->
                val parts = interval.split("-")
                if (parts.size == 2) {
                    val start = parts[0].toLongOrNull()
                    val end = parts[1].toLongOrNull()
                    if (start != null && end != null) {
                        TimeInterval(start, end)
                    } else {
                        null
                    }
                } else {
                    null
                }
            },
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

    private fun getActiveId(doc: Document): Int {
        val regex = """const\s+fileList\s*=\s*JSON\.parse\(\s*(["'`])(.*?)\1\s*\)"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)

        val scriptContent = doc.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("const fileList") }
            ?: throw AllohaParsingException("Failed to find active media id in Alloha script payload.")
        val jsonString = regex.find(scriptContent)?.groupValues?.get(2)
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

    private fun a6(s: String): String {
        val length = s.length
        if (length <= 0) {
            return s
        }
        var u = 0
        while ((1 shl u) < length) {
            u++
        }
        fun ax(z: Int): Int {
            if (z == 0) {
                return u
            }
            var g = 0
            var z = z
            while ((1 and z) == 0) {
                g++
                z = z shr 1
            }
            return g
        }

        val aj = IntArray(u + 1)
        for (i in 0 until length) {
            aj[ax(i)]++
        }

        val aE = Array(u + 1) { "" }
        var pos = 0
        for (i in 0..u) {
            aE[i] = s.substring(pos, pos + aj[i])
            pos += aj[i]
        }

        val pointers = IntArray(u + 1)
        val res = CharArray(length)
        for (i in 0 until length) {
            val r = ax(i)
            res[i] = aE[r][pointers[r]++]
        }

        return String(res)
    }

    private fun a7(s: String): String {
        val length = s.length
        if (length <= 0) {
            return s
        }
        var u = 0
        while ((1 shl u) < length) {
            u++
        }
        fun ax(g: Int): Int {
            if (g == 0) {
                return 0
            }
            var d = 0
            var g = g
            while (g > 0) {
                d++
                g = g shr 1
            }
            return d
        }

        val aj = IntArray(u + 1)
        for (i in 0 until length) {
            aj[ax(i)]++
        }

        val aE = Array(u + 1) { "" }
        var pos = 0
        for (i in u downTo 0) {
            aE[i] = s.substring(pos, pos + aj[i])
            pos += aj[i]
        }

        val pointers = IntArray(u + 1)
        val res = CharArray(length)
        for (i in 0 until length) {
            val r = ax(i)
            res[i] = aE[r][pointers[r]++]
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

    private fun getNextPrime(n: Int): Int {
        var c = maxOf(1, n)
        while (!isPrime(c)) {
            c++
        }
        return c
    }

    private fun unscramble(s: String): String {
        val length = s.length
        if (length <= 1) {
            return s
        }

        val ax = getNextPrime(length + 1)

        val aj = BooleanArray(length)
        val ac = IntArray(length)
        var acEl = 0
        var aE = 0

        while (acEl < length) {
            aE = (aE + 2) % ax
            if (aE < length && !aj[aE]) {
                ac[acEl++] = aE
                aj[aE] = true
            }
        }

        val aA = CharArray(length)
        for (av in 0 until length) {
            aA[ac[av]] = s[av]
        }

        return String(aA)
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
    val isDefault: Boolean
)

private sealed class AllohaParserException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

private class AllohaApiException(message: String) : AllohaParserException(message)

private class AllohaParsingException(message: String, cause: Throwable? = null) :
    AllohaParserException(message, cause)