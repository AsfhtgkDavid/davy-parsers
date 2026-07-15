package dev.daika.davyparsers

interface Parser {
    suspend fun parse(iframeUrl: String): PlayerData?
}