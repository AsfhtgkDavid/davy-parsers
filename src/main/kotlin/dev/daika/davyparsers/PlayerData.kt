package dev.daika.davyparsers

data class Translation(
    val id: String,
    val name: String,
    val streams: List<MediaStream>
)

data class MediaStream(
    val urls: List<String>,
    val quality: String,
)

data class TimeInterval(
    val start: Long,
    val end: Long
)

data class SubtitleTrack(
    val kind: String,
    val label: String,
    val language: String,
    val src: String,
    val isDefault: Boolean = false
)

data class PlayerData(
    val translations: List<Translation>,
    val skipTimes: List<TimeInterval> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList()
)