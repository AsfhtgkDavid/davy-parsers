package dev.daika.davyparsers

interface Parser {
    fun parse(iframeUrl: String): PlayerData
}