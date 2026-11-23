// Part of YummyPC — порт YummyTV (https://github.com/Helandy/YummyTV, CC BY-NC 4.0) на ПК.
package su.afk.yummy.pc.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** JNA-биндинги к libmpv (mpv-dev): плеер рисует кадры в память через render API. */
internal interface LibmpvNative : Library {

    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): String?

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(
        ctx: Pointer,
        callback: RenderUpdateCallback,
        callbackCtx: Pointer?,
    )
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    fun interface RenderUpdateCallback : Callback {
        fun invoke(ctx: Pointer?)
    }

    companion object {
        val INSTANCE: LibmpvNative by lazy { load() }

        private fun load(): LibmpvNative {
            val candidates = buildList {
                System.getenv("YUMMYPC_LIBMPV")?.let { add(File(it)) }
                System.getProperty("jpackage.app-path")?.let { exe ->
                    add(File(File(exe).parentFile, "libmpv-2.dll"))
                }
                add(File(System.getProperty("user.dir"), "libmpv-2.dll"))
                add(File("C:/yummypc-rebuild/libmpv/extracted/libmpv-2.dll"))
            }
            val dll = candidates.firstOrNull { it.isFile }
                ?: error("libmpv-2.dll не найден (положите рядом с YummyPC.exe)")
            return Native.load(dll.absolutePath, LibmpvNative::class.java)
        }
    }
}
