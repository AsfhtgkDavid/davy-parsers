# DAVY Parsers

**DAVY Parsers** is a Kotlin/JVM library for extracting player data from supported iframe sources. It currently includes
parsers for Kodik and Alloha and returns translations, stream URLs, subtitles, and skip intervals.

## Installation

The project targets **Java 21**.

Add the dependency to your `pom.xml`:

```xml

<dependency>
    <groupId>dev.daika</groupId>
    <artifactId>davy-parsers</artifactId>
    <version>1.0.0</version>
</dependency>
```

Or with Gradle Kotlin DSL:

```kotlin
dependencies {
    implementation("dev.daika:davy-parsers:1.0.0")
}
```

Or with Gradle Groovy DSL:

```groovy
dependencies {
    implementation 'dev.daika:davy-parsers:1.0.0'
}
```

## Usage

```kotlin
import dev.daika.davyparsers.Parser
import okhttp3.OkHttpClient

val client = OkHttpClient()
val iframeUrl = "https://example.com/embed"
val referer = "https://example.com"

val parser = Parser.getParserForUrl(iframeUrl, Parser.getAllParsers(client))
    ?: error("Unsupported iframe URL")

val playerData = parser.parse(iframeUrl, referer)
```

`parse()` is a suspending function, so call it from a coroutine.

## Contributing

1. Fork the repository and create a feature branch.
2. Make your changes.
3. Open a pull request.

## License

Licensed under the **GNU Affero General Public License v3.0**. See the [LICENSE](LICENSE) file for details.
