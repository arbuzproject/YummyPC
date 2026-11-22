// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Запуск mpv как внешнего плеера.
 *
 * В Android-версии видео играет Media3/ExoPlayer внутри приложения; на Windows mpv закрывает
 * те же задачи (HLS, mp4, HTTP-заголовки). Лог mpv пишется в `mpv-last.log` рядом с приложением —
 * по нему видно, почему поток не открылся.
 */
object MpvLauncher {

    private val candidates = listOf(
        { File("tools/mpv/mpv.exe") },
        { File("../tools/mpv/mpv.exe") },
        { File("../../tools/mpv/mpv.exe") },
        { File("../../../../tools/mpv/mpv.exe") },
        { File("E:/neuiroslop/yummypc/tools/mpv/mpv.exe") },
    )

    fun findMpv(): File? =
        System.getenv("YUMMYPC_MPV")?.let(::File)?.takeIf { it.isFile }
            ?: candidates.asSequence().map { it() }.firstOrNull { it.isFile }
            ?: searchNearExecutable()
            ?: searchUpFromWorkingDir()

    /** mpv рядом с установленным приложением: `<app>/tools/mpv/mpv.exe` и `<app>/mpv.exe`. */
    private fun searchNearExecutable(): File? {
        val appPath = System.getProperty("jpackage.app-path")?.let(::File)?.parentFile ?: return null
        return listOf(
            File(appPath, "mpv/mpv.exe"),
            File(appPath, "tools/mpv/mpv.exe"),
            File(appPath, "mpv.exe"),
        ).firstOrNull { it.isFile }
    }

    /** Поднимаемся от рабочего каталога: `tools/mpv/mpv.exe` в любом из родителей. */
    private fun searchUpFromWorkingDir(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val current = dir ?: return null
            val candidate = File(current, "tools/mpv/mpv.exe")
            if (candidate.isFile) return candidate
            dir = current.parentFile
        }
        return null
    }

    fun play(
        url: String,
        headers: Map<String, String> = emptyMap(),
        title: String? = null,
        referer: String? = null,
    ): Result<Process> = runCatching {
        val mpv = findMpv() ?: error("mpv не найден: положите mpv.exe в tools/mpv/ или задайте YUMMYPC_MPV")
        val args = mutableListOf<String>()
        args += "--no-config"
        args += "--force-window=yes"
        args += "--keep-open=no"
        args += "--osc=yes"
        args += "--msg-level=all=status"
        args += "--user-agent=" + (headers["User-Agent"] ?: DEFAULT_UA)
        val headerFields = headers.entries
            .filterNot { it.key.equals("User-Agent", ignoreCase = true) }
            .map { "${it.key}: ${it.value}" }
        if (headerFields.isNotEmpty()) args += "--http-header-fields=" + headerFields.joinToString(",")
        referer?.let { args += "--referrer=$it" }
        args += "--title=" + (title ?: "YummyPC")
        args += url

        val logFile = logFile()
        Files.writeString(
            logFile.toPath(),
            buildString {
                appendLine("# ${java.time.LocalDateTime.now()}")
                appendLine("# ${mpv.absolutePath}")
                args.forEach { appendLine("# arg: $it") }
            },
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        ProcessBuilder(listOf(mpv.absolutePath) + args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
    }

    fun logFile(): File {
        val dir = File(System.getProperty("user.home"), ".yummypc")
        dir.mkdirs()
        return File(dir, "mpv-last.log")
    }

    /** Ссылка уже является медиафайлом/плейлистом, а не страницей балансера. */
    fun isDirectStream(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".m3u8") || clean.endsWith(".webm") ||
            clean.endsWith(".mkv") || clean.endsWith(".mpd")
    }

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
}
