package dev.daika.davyparsers

/** Defines a parser that extracts player data from an iframe URL. */
interface Parser {
    /** Parses the given iframe URL into player data, or returns null when unavailable.
     *  @param iframeUrl The URL of the iframe to parse.
     *  @param referer The referer URL to use for the request.
     *  @return The parsed player data.
     */
    suspend fun parse(iframeUrl: String, referer: String): PlayerData
}