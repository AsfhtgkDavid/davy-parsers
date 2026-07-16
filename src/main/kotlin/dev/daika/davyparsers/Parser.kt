package dev.daika.davyparsers

import okhttp3.OkHttpClient

/** Defines a parser that extracts player data from an iframe URL. */
interface Parser {
    /** Parses the given iframe URL into player data.
     *  @param iframeUrl The URL of the iframe to parse.
     *  @param referer The referer URL to use for the request.
     *  @return The parsed player data.
     *  @throws ParserException If an error occurs while parsing.
     */
    suspend fun parse(iframeUrl: String, referer: String): PlayerData

    /** Determines if this parser can handle the given iframe URL.
     *  @param iframeUrl The URL of the iframe to check.
     *  @return True if this parser can handle the URL, false otherwise.
     */
    fun canParse(iframeUrl: String): Boolean

    companion object {
        /** Returns a list of all available parsers.
         *  @param client The OkHttpClient to use for network requests.
         *  @return A list of all available parsers.
         */
        fun getAllParsers(client: OkHttpClient): List<Parser> {
            return listOf(
                KodikParser(client),
                AllohaParser(client),
            )
        }

        /** Returns the first parser that can handle the given iframe URL.
         *  @param iframeUrl The URL of the iframe to parse.
         *  @param parsers The list of parsers to check.
         *  @return The first parser that can handle the URL, or null if none can.
         */
        fun getParserForUrl(iframeUrl: String, parsers: List<Parser>): Parser? {
            return parsers.firstOrNull { it.canParse(iframeUrl) }
        }
    }
}

/** Represents an exception that occurs during parsing. */
sealed class ParserException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)