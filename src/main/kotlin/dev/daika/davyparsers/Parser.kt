package dev.daika.davyparsers

/** Defines a parser that extracts player data from an iframe URL. */
interface Parser {
    /** Parses the given iframe URL into player data, or returns null when unavailable. */
    suspend fun parse(iframeUrl: String): PlayerData?
}