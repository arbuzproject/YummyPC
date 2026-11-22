// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * CLI экстрактора Kodik: принимает iframe-URL, печатает прямой HLS-URL, заголовки и качества.
 *
 * Запуск:
 * ```
 * .\gradlew.bat --console=plain :extractors:run --args="<iframe-url>"
 * .\gradlew.bat --console=plain :extractors:run --args="<iframe-url> --verify"
 * ```
 *
 * Флаг `--verify` делает GET ссылки и проверяет, что плейлист начинается с `#EXTM3U`.
 *
 * Коды возврата: 0 — успех; 1 — экстрактор отказал; 2 — неверные аргументы; 3 — ссылка получена,
 * но плейлист не HLS. Gradle пробрасывает ненулевой код как падение таска.
 */
fun main(args: Array<String>) {
    val options = args.filter { it.startsWith("--") }.toSet()
    if (args.isEmpty() || options.contains("--help") || options.contains("-h")) {
        printUsage()
        if (args.isEmpty()) exitProcess(EXIT_BAD_ARGUMENTS)
        return
    }

    val iframeUrl = args.firstOrNull { !it.startsWith("--") }
    if (iframeUrl == null) {
        System.err.println("No iframe URL provided.")
        printUsage()
        exitProcess(EXIT_BAD_ARGUMENTS)
    }

    val verify = options.contains("--verify")
    val exitCode = runBlocking { extractAndPrint(iframeUrl, verify) }
    exitProcess(exitCode)
}

private suspend fun extractAndPrint(iframeUrl: String, verify: Boolean): Int {
    println("iframe: $iframeUrl")
    val resolver = KodikResolver(onStage = { stage -> println("[kodik] $stage") })
    val result = resolver.resolve(iframeUrl)

    val stream = result.getOrElse { error ->
        System.err.println("FAILED: ${error.message ?: error::class.java.simpleName}")
        return EXIT_RESOLVE_FAILED
    }

    println()
    println("stream: ${stream.url}")
    stream.headers.forEach { (name, value) -> println("header: $name: $value") }
    println("qualities:")
    stream.qualities.forEach { (label, url) -> println("  $label -> $url") }

    if (!verify) return EXIT_OK

    println()
    val probe = try {
        PlaylistVerifier().check(stream.url, headers = stream.headers)
    } catch (e: Exception) {
        System.err.println("verify: FAILED: ${e.message.orEmpty()}")
        return EXIT_VERIFY_FAILED
    }
    println("verify: HTTP ${probe.statusCode}, first line: \"${probe.firstLine}\"")
    return if (probe.isHlsPlaylist) {
        println("verify: OK - playlist starts with #EXTM3U")
        EXIT_OK
    } else {
        System.err.println("verify: FAILED - response does not start with #EXTM3U")
        EXIT_VERIFY_FAILED
    }
}

private fun printUsage() {
    println(
        """
        Kodik extractor CLI — iframe URL -> direct HLS stream.

        Usage:
          gradlew :extractors:run --args="<iframe-url> [--verify]"

        Options:
          --verify   GET the resolved playlist and check it starts with #EXTM3U
          --help     print this help

        Example:
          .\gradlew.bat --console=plain :extractors:run --args="https://kodikplayer.com/season/77530/9f8291faa668c3357e0822ba23c3a366/720p?translations=false&only_episode=true&only_season=true&episode=1 --verify"
        """.trimIndent(),
    )
}

private const val EXIT_OK = 0
private const val EXIT_RESOLVE_FAILED = 1
private const val EXIT_BAD_ARGUMENTS = 2
private const val EXIT_VERIFY_FAILED = 3
