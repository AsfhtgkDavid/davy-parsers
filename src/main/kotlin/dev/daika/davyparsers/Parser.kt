package dev.daika.davyparsers

/** Defines a parser that extracts player data from an iframe URL. */
interface Parser {
    /** Parses the given iframe URL into player data.
     *  @param iframeUrl The URL of the iframe to parse.
     *  @param referer The referer URL to use for the request.
     *  @return The parsed player data.
     *  @throws ParserException If an error occurs while parsing.
     */
    suspend fun parse(iframeUrl: String, referer: String): PlayerData
}

sealed class ParserException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)