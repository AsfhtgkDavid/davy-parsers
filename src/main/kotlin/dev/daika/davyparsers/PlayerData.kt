package dev.daika.davyparsers

/** Describes one audio translation and its available streams. */
data class Translation(
    val id: String,
    val name: String,
    val streams: List<MediaStream>
)

/** Describes a playable stream variant for a specific quality. */
data class MediaStream(
    val urls: List<String>,
    val quality: String,
)

/** Defines a playback interval that should be skipped automatically. */
data class TimeInterval(
    val start: Long,
    val end: Long
)

/** Describes a subtitle track exposed by the player source. */
data class SubtitleTrack(
    val kind: String,
    val label: String,
    val language: String,
    val src: String,
    val isDefault: Boolean = false
)

/** Aggregates translations, skip intervals, and subtitles for a player. */
data class PlayerData(
    val translations: List<Translation>,
    val skipTimes: List<TimeInterval> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList()
)