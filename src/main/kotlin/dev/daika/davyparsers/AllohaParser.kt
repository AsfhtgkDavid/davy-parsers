package dev.daika.davyparsers

import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

private const val userAgent =
    "Mozilla/5.0 (X11; Linux x86_64; rv:159.0) Gecko/20100101 Firefox/159.0"
private const val acceptLanguage = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
private const val allohaOrigin = "https://alloha.yani.tv"
private const val allohaBnsiPath = "$allohaOrigin/bnsi/movies"
private const val clientFingerprintHash = "d2c8a63ca1d2ac9885ddce7c8478f780d67a6bd9268d39342c35a19b6641260b"
private val fileListRegex =
    """const\s+fileList\s*=\s*JSON\.parse\(\s*(["'`])(.*?)\1\s*\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
private val apiKeyRegex = """['"]([a-zA-Z0-9]{340,420})['"]""".toRegex(RegexOption.DOT_MATCHES_ALL)

/** A parser implementation for the Alloha player source. */
class AllohaParser(private val client: OkHttpClient) : Parser {
    private val json = Json { ignoreUnknownKeys = true }

    private val wsClient: OkHttpClient by lazy {
        client.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeWebSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var bnsiRefreshJob: Job? = null
    private var onHeadersChanged: ((Map<String, String>) -> Unit)? = null

    private var savedPnr: String? = null
    private var savedPnk: String? = null
    private var currentResolution: String = "1080"
    private var currentAudioId: String = "1"

    private val currentPlaybackTimeSec = AtomicLong(0L)
    private val isPlaying = AtomicBoolean(true)
    private val isSessionClosed = AtomicBoolean(false)
    private var reconnectAttempts = 0

    override suspend fun parse(
        iframeUrl: String,
        referer: String,
        onHeadersChanged: (Map<String, String>) -> Unit
    ): PlayerData {
        this.onHeadersChanged = onHeadersChanged
        val iframeDocument = loadIframeDocument(iframeUrl, referer)
        val requestSeed = iframeDocument.selectFirst("meta[name=viewporti]")?.attr("content")
            ?: throw AllohaParsingException("Missing viewporti metadata required for request signing.")
        val token = extractToken(iframeUrl)
        val borth = buildBorthHeader(requestSeed)
        val activeId = extractActiveId(iframeDocument)
        val bnsiDto = fetchBnsiData(activeId, token, borth, iframeUrl)

        val defaultSource = bnsiDto.hlsSource.firstOrNull { it.isDefault } ?: bnsiDto.hlsSource.first()
        val defaultQuality = when {
            defaultSource.quality.containsKey("1080") -> "1080"
            defaultSource.quality.containsKey("720") -> "720"
            else -> defaultSource.quality.keys.firstOrNull() ?: "720"
        }

        val edgeHash = if (!bnsiDto.pnr.isNullOrBlank() && !bnsiDto.pnk.isNullOrBlank()) {
            startWebSocketSession(
                pnr = bnsiDto.pnr,
                pnk = bnsiDto.pnk,
                resolution = defaultQuality,
                audioId = defaultSource.audioId
            )
        } else {
            null
        }

        val headers = fetchHeaders(
            iframeUrl = iframeUrl,
            document = iframeDocument,
            acceptsControls = edgeHash ?: clientFingerprintHash
        )
        return mapPlayerData(bnsiDto, headers)
    }

    override fun canParse(iframeUrl: String): Boolean {
        return iframeUrl.contains("alloha.yani.tv")
    }

    private suspend fun resolveWorkingUrls(urls: List<String>, headers: Map<String, String>): List<String> {
        if (urls.size <= 1) return urls

        // Быстрая проверка первого зеркала
        val firstUrl = urls.first()
        val isFirstWorking = withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder().url(firstUrl).head()
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                client.newCall(reqBuilder.build()).execute().use { response ->
                    response.isSuccessful
                }
            } catch (_: Exception) {
                false
            }
        }

        // Если первое зеркало выдало 403 / ошибку — меняем порядок, ставя рабочее первым
        return if (isFirstWorking) {
            urls
        } else {
            urls.reversed()
        }
    }

    override fun sendEvent(event: ParserEvent) {
        when (event) {
            is ParserEvent.RESUMED -> {
                currentPlaybackTimeSec.set(event.currentTime)
                isPlaying.set(true)
                sendWsEvent("playback_start", event.currentTime)
                sendWsEvent("resumed", event.currentTime)
                startHeartbeat()
            }

            is ParserEvent.PAUSED -> {
                currentPlaybackTimeSec.set(event.currentTime)
                isPlaying.set(false)
                stopHeartbeat()
                sendWsEvent("paused", event.currentTime)
            }

            is ParserEvent.SEEKED -> {
                currentPlaybackTimeSec.set(event.currentTime)
                sendWsEvent("seeked", event.currentTime)
            }

            is ParserEvent.TRACK_CHANGED -> {
                currentPlaybackTimeSec.set(event.currentTime)
                currentResolution = event.resolution
                currentAudioId = event.audioId
                sendWsEvent("playback_start", event.currentTime)
                sendWsEvent("resumed", event.currentTime)
            }
        }
    }

    override fun closeSession() {
        isSessionClosed.set(true)
        stopHeartbeat()
        reconnectJob?.cancel()
        reconnectJob = null
        bnsiRefreshJob?.cancel()
        bnsiRefreshJob = null
        savedPnr = null
        savedPnk = null
        activeWebSocket?.close(1000, null)
        activeWebSocket = null
    }

    private suspend fun startWebSocketSession(
        pnr: String,
        pnk: String,
        resolution: String,
        audioId: String
    ): String? {
        closeSession()
        isSessionClosed.set(false)
        savedPnr = pnr
        savedPnk = pnk
        currentResolution = resolution
        currentAudioId = audioId
        currentPlaybackTimeSec.set(0L)
        isPlaying.set(true)
        reconnectAttempts = 0

        val initialEdgeHash = CompletableDeferred<String?>()
        connectWebSocket(initialEdgeHash)
        startHeartbeat()

        return withTimeoutOrNull(4_000L) {
            initialEdgeHash.await()
        }
    }

    private suspend fun fetchHeaders(
        iframeUrl: String,
        document: Document,
        acceptsControls: String
    ): Map<String, String> {
        val originHost = "https://${iframeUrl.toHttpUrl().host}"
        val headers = mutableMapOf(
            "Origin" to originHost,
            "Referer" to "$originHost/",
            "User-Agent" to userAgent,
            "Accept-Language" to acceptLanguage,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Accepts-Controls" to acceptsControls
        )

        val appScript = document.select("script[src]")
            .mapNotNull { it.attr("src") }
            .firstOrNull { it.contains("/app.") || it.contains("app.") }

        if (appScript != null) {
            val scriptUrl = if (appScript.startsWith("http")) appScript else "$originHost/${
                appScript.removePrefix("./").removePrefix("/")
            }"
            val request = Request.Builder()
                .url(scriptUrl)
                .header("User-Agent", userAgent)
                .header("Referer", iframeUrl)
                .build()

            client.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val apiKeyMatch = apiKeyRegex.find(body)
                    if (apiKeyMatch != null) {
                        headers["Authorizations"] = "Bearer ${apiKeyMatch.groupValues[1]}"
                    }
                }
            }
        }

        return headers
    }

    private fun connectWebSocket(initialEdgeHash: CompletableDeferred<String?>? = null) {
        val pnr = savedPnr ?: return
        val pnk = savedPnk ?: return
        if (isSessionClosed.get()) return

        val encodedSid = URLEncoder.encode(pnk, "UTF-8")
        val wsUrl = "$pnr?sid=$encodedSid&v=2.1&t=${System.currentTimeMillis()}"

        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", userAgent)
            .header("Origin", allohaOrigin)
            .header("Accept-Language", acceptLanguage)
            .build()

        activeWebSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                val startMsg = buildWsPayload(
                    type = "playback_start",
                    currentTime = currentPlaybackTimeSec.get(),
                    resolution = currentResolution,
                    trackId = currentAudioId
                )
                webSocket.send(startMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = json.decodeFromString<AllohaWsConfigUpdateDto>(text)
                    if (msg.type == "config_update" && !msg.edgeHash.isNullOrBlank()) {
                        onHeadersChanged?.invoke(mapOf("Accepts-Controls" to msg.edgeHash))
                        initialEdgeHash?.let {
                            if (!it.isCompleted) it.complete(msg.edgeHash)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                initialEdgeHash?.let {
                    if (!it.isCompleted) it.complete(null)
                }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                initialEdgeHash?.let {
                    if (!it.isCompleted) it.complete(null)
                }
                if (code != 1000) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (isSessionClosed.get() || reconnectAttempts >= 30) return
        reconnectJob?.cancel()
        reconnectJob = wsScope.launch {
            val delayMs = min((1000.0 * 1.5.pow(reconnectAttempts.toDouble())).toLong(), 15_000L)
            reconnectAttempts++
            delay(delayMs)
            if (!isSessionClosed.get()) {
                connectWebSocket(null)
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = wsScope.launch {
            while (isActive && !isSessionClosed.get()) {
                delay(15_000L)
                if (isPlaying.get()) {
                    val timeSec = currentPlaybackTimeSec.addAndGet(15L)
                    sendWsEvent("playing", timeSec)
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendWsEvent(type: String, currentTimeSec: Long) {
        val ws = activeWebSocket ?: return
        val payload = buildWsPayload(
            type = type,
            currentTime = currentTimeSec,
            resolution = currentResolution,
            trackId = currentAudioId
        )
        val sent = ws.send(payload)
        if (!sent && !isSessionClosed.get()) {
            scheduleReconnect()
        }
    }

    private fun buildWsPayload(
        type: String,
        currentTime: Long,
        resolution: String,
        trackId: String
    ): String {
        val ts = System.currentTimeMillis()
        return """{"type":"$type","current_time":$currentTime,"resolution":"$resolution","track_id":"$trackId","speed":1,"subtitle":-1,"ts":$ts}"""
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
            Jsoup.parse(response.body.byteStream(), "UTF-8", response.request.url.toString())
        }
    }

    private fun extractToken(iframeUrl: String): String {
        return iframeUrl.toHttpUrlOrNull()?.queryParameter("token")
            ?: throw AllohaParsingException("Missing 'token' query parameter in iframe URL.")
    }

    private fun buildBorthHeader(requestSeed: String): String {
        return "$clientFingerprintHash|${decodeBorthSeed(requestSeed)}"
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
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Accept-Language", acceptLanguage)
            .build()

        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw AllohaApiException("Alloha BNSI request failed with HTTP ${response.code}.")
            }

            val rawBody = response.body.string()
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

    private fun buildBnsiRequestBody(token: String) = FormBody.Builder()
        .add("token", token)
        .add("av1", "false")
        .add("autoplay", "0")
        .add("audio", "")
        .add("subtitle", "")
        .build()

    private suspend fun mapPlayerData(bnsiDto: AllohaBnsiDto, headers: Map<String, String>): PlayerData {
        val guard = headers["Authorizations"]?.removePrefix("Bearer ")?.trim().orEmpty()
        val ac = headers["Accepts-Controls"].orEmpty()

        return PlayerData(
            translations = bnsiDto.hlsSource.map { source ->
                Translation(
                    id = source.audioId,
                    name = source.label,
                    streams = source.quality.map { (quality, url) ->
                        MediaStream(urls = resolveWorkingUrls(parseUrls(url), headers), quality = quality)
                    },
                    isDefault = source.isDefault
                )
            },
            skipTimes = if (bnsiDto.skipTime != null) parseSkipTimes(bnsiDto.skipTime) else emptyList(),
            subtitles = bnsiDto.tracks?.map { track ->
                val signedSrc = if (guard.isNotEmpty() && ac.isNotEmpty()) {
                    "${track.src}?t=$guard&ac=$ac"
                } else {
                    track.src
                }
                SubtitleTrack(
                    kind = track.kind,
                    label = track.label,
                    language = track.language,
                    src = signedSrc,
                    isDefault = track.isDefault
                )
            } ?: emptyList(),
            headers = headers
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
    val skipTime: String? = null,
    val hlsSource: List<AllohaHlsSourceDto>,
    val tracks: List<AllohaTrackDto>? = null,
    val pnr: String? = null,
    val pnk: String? = null,
    val time: Long? = null
)

@Serializable
private data class AllohaWsConfigUpdateDto(
    val type: String? = null,
    @SerialName("edge_hash")
    val edgeHash: String? = null
)

@Serializable
private data class AllohaHlsSourceDto(
    val label: String,
    val quality: Map<String, String>,
    val audioId: String,
    @SerialName("default")
    val isDefault: Boolean = false
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