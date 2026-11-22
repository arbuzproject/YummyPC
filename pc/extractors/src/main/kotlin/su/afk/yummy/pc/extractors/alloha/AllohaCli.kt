// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0).
package su.afk.yummy.pc.extractors.alloha

import kotlinx.coroutines.runBlocking
import su.afk.yummy.pc.extractors.PlaylistVerifier
import su.afk.yummy.pc.extractors.ResolvedStream
import kotlin.system.exitProcess

/**
 * CLI экстрактора Alloha: принимает iframe-URL, печатает прямой HLS-URL, заголовки и качества.
 *
 * Запуск (после того как ведущий агент переключит `application.mainClass` модуля на этот класс или
 * добавит отдельный JavaExec-таск):
 * ```
 * .\gradlew.bat --console=plain :extractors:run --args="<iframe-url>"
 * .\gradlew.bat --console=plain :extractors:run --args="<iframe-url> --verify --mpv=tools/mpv/mpv.exe"
 * ```
 *
 * Флаги:
 * - `--verify` — GET ссылки с заголовками потока, проверка что ответ начинается с `#EXTM3U`;
 * - `--mpv=<путь>` — сразу открыть ссылку во внешнем mpv (`--no-config --vo=null --ao=null --frames=5`)
 *   и вернуть его код; сессия Alloha при этом остаётся живой до конца проигрывания.
 *
 * Коды возврата: 0 — успех; 1 — экстрактор отказал; 2 — неверные аргументы; 3 — ссылка получена, но
 * плейлист не HLS или mpv не смог открыть поток.
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
    val mpvPath = args.firstOrNull { it.startsWith("--mpv=") }?.substringAfter('=')?.takeIf(String::isNotBlank)

    val exitCode = runBlocking {
        extractAndPrint(iframeUrl, verify = options.contains("--verify"), mpvPath = mpvPath)
    }
    exitProcess(exitCode)
}

private suspend fun extractAndPrint(iframeUrl: String, verify: Boolean, mpvPath: String?): Int {
    println("iframe: $iframeUrl")
    val resolver = AllohaResolver(onStage = { stage -> println("[alloha] $stage") })
    val stream = resolver.resolve(iframeUrl).getOrElse { error ->
        System.err.println("FAILED: ${error.message ?: error::class.java.simpleName}")
        return EXIT_RESOLVE_FAILED
    }

    println()
    println("stream: ${stream.url}")
    stream.headers.forEach { (name, value) -> println("header: $name: $value") }
    println("qualities:")
    stream.qualities.forEach { (label, url) -> println("  $label -> $url") }

    var exitCode = EXIT_OK
    if (verify) {
        println()
        exitCode = verifyPlaylist(stream)
    }
    if (mpvPath != null) {
        println()
        val mpvCode = runMpv(mpvPath, stream)
        println("mpv: exit=$mpvCode")
        if (mpvCode != 0 && exitCode == EXIT_OK) exitCode = EXIT_VERIFY_FAILED
    }
    AllohaResolver.releaseActiveSession()
    return exitCode
}

private suspend fun verifyPlaylist(stream: ResolvedStream): Int {
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

/**
 * Открывает ссылку в mpv с заголовками потока. Значения с запятыми (Accept-Language, sec-ch-ua)
 * упрощаются до одного элемента: `--http-header-fields` разделяет поля запятыми, а серверу важен
 * факт наличия заголовка, а не полный q-список.
 */
private fun runMpv(mpvPath: String, stream: ResolvedStream): Int {
    val fields = mpvHeaderFields(stream.headers)
    val command = listOf(
        mpvPath,
        "--no-config",
        "--vo=null",
        "--ao=null",
        "--frames=5",
        "--http-header-fields=$fields",
        stream.url,
    )
    println("mpv: ${command.dropLast(1).joinToString(" ")} <url>")
    return try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().forEachLine { line -> println("[mpv] $line") }
        process.waitFor()
    } catch (error: Exception) {
        System.err.println("mpv: failed to start: ${error.message}")
        -1
    }
}

private fun mpvHeaderFields(headers: Map<String, String>): String =
    headers.entries.joinToString(",") { (name, value) ->
        val normalized = when (name.lowercase()) {
            "accept-language", "sec-ch-ua" -> value.substringBefore(',')
            else -> value
        }
        "$name: $normalized"
    }

private fun printUsage() {
    println(
        """
        Alloha extractor CLI — iframe URL -> direct HLS stream.

        Usage:
          gradlew :extractors:run --args="<iframe-url> [--verify] [--mpv=<path>]"

        Options:
          --verify         GET the resolved playlist and check it starts with #EXTM3U
          --mpv=<path>     play the resolved url in mpv (5 frames, null output)
          --help           print this help

        Example:
          .\gradlew.bat --console=plain :extractors:run --args="https://alloha.yani.tv/?token_movie=...&translation=213&season=2&episode=1&token=...&hidden=translation,season,episode --mpv=tools/mpv/mpv.exe"
        """.trimIndent(),
    )
}

private const val EXIT_OK = 0
private const val EXIT_RESOLVE_FAILED = 1
private const val EXIT_BAD_ARGUMENTS = 2
private const val EXIT_VERIFY_FAILED = 3
