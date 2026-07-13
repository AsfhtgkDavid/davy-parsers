package dev.daika.davyparsers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class MediaSource(
    val url: String,
    val quality: String? = null
)

data class TimeInterval(
    val start: Long,
    val end: Long
)

@Serializable
data class SubtitleTrack(
    val kind: String,
    val label: String,
    val language: String,
    val src: String,
    @SerialName("default")
    val isDefault: Boolean = false
)

data class PlayerData(
    val streams: List<MediaSource>,
    val skipTimes: List<TimeInterval>,
    val subtitles: List<SubtitleTrack>
)