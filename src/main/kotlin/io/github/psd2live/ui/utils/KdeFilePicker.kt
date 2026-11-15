package io.github.psd2live.ui.utils

import com.sun.jna.Native
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Personal KDE integration: the chooser lives outside the JVM, so it does not load GTK alongside Skia. */
internal object KdeFilePicker {
    enum class Kind(val extension: String, val filter: String) {
        PROJECT("psd2live", "PSD2Live (*.psd2live *.PSD2LIVE)"),
        PSD("psd", "Photoshop (*.psd *.PSD)"),
    }

    internal data class Result(val directory: File, val handled: Boolean, val file: File? = null)

    @Volatile private var activeOwner: Window? = null

    internal fun enabled(
        osName: String = System.getProperty("os.name").orEmpty(),
        desktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
        disabled: Boolean = System.getProperty("psd2live.kdePicker") == "false",
    ): Boolean = !disabled && osName.contains("linux", ignoreCase = true) &&
        desktop.split(':').any { it.equals("KDE", ignoreCase = true) }

    /** Resolve the user's configured (possibly relocated/localised) Pictures directory, never the working drive. */
    internal fun picturesDirectory(
        home: File = File(System.getProperty("user.home")),
        lookup: () -> String? = ::xdgPictures,
    ): File {
        val configured = runCatching { lookup() }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::File)
        return configured?.takeIf { it.isAbsolute && it.isDirectory }
            ?: File(home, "Pictures").takeIf { it.isDirectory }
            ?: home
    }

    private fun xdgPictures(): String? {
        val process = ProcessBuilder("xdg-user-dir", "PICTURES").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return try {
            process.outputStream.close()
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) null
            else process.inputStream.bufferedReader().use { it.readText().removeSuffix("\n").removeSuffix("\r") }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    /** Reimport starts beside its source PSD when available; unknown/moved sources start in Pictures. */
    internal fun initialDirectory(initialPath: String?, pictures: () -> File = { picturesDirectory() }): File {
        val path = initialPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isAbsolute }
        val directory = path?.let { if (it.isDirectory) it else it.parentFile }
        return directory?.takeIf { it.isDirectory } ?: pictures()
    }

    internal fun command(ownerId: Long, directory: File, title: String, kind: Kind = Kind.PROJECT): List<String> = listOf(
        "kdialog", "--print-winid", "--attach", "0x${ownerId.toString(16)}", "--title", title,
        "--getopenfilename", directory.absolutePath, kind.filter,
    )

    internal fun result(exitCode: Int, output: String, directory: File, kind: Kind = Kind.PROJECT): Result {
        var remaining = output
        var shown = false
        // Qt can report both the native KDE dialog and its hidden QFileDialog wrapper before deleteLater runs.
        while (remaining.substringBefore('\n').trimEnd('\r').matches(Regex("winId: [0-9]+"))) {
            shown = true
            remaining = remaining.substringAfter('\n', "")
        }
        // kdialog also returns 1 for argument/startup errors. Only count it as Cancel after a dialog was shown.
        if (!shown) return Result(directory, handled = false)
        return when (exitCode) {
            0 -> Result(directory, handled = true, file = remaining.removeSuffix("\n").removeSuffix("\r")
                .takeIf { it.isNotEmpty() }?.let(::File)?.takeIf {
                    it.isAbsolute && it.isFile && it.name.endsWith(".${kind.extension}", ignoreCase = true)
                })
            1 -> Result(directory, handled = true) // User cancellation must not open the fallback chooser.
            else -> Result(directory, handled = false)
        }
    }

    /** Raising the transient's owner also raises its KDE dialog; no second process is launched. */
    fun focus(): Boolean {
        val owner = activeOwner?.takeIf { it.isDisplayable } ?: return false
        EventQueue.invokeLater { owner.toFront() }
        return true
    }

    /** Called on the EDT. A secondary event loop keeps Compose painting while the external chooser is modal. */
    fun open(owner: Window?, title: String, kind: Kind = Kind.PROJECT, initialPath: String? = null): Result {
        check(EventQueue.isDispatchThread())
        val fallback = File(System.getProperty("user.home"))
        val selected = AtomicReference(Result(fallback, handled = false))
        val process = AtomicReference<Process?>()
        val closed = AtomicBoolean(false)
        val eventLoop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        val useNative = enabled() && owner?.isShowing == true &&
            Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit"
        val ownerId = if (useNative) runCatching { Native.getWindowID(owner) }.getOrNull() else null
        val wasEnabled = owner?.isEnabled == true
        val closeListener = object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) { closed.set(true); process.get()?.destroy() }
        }
        val shutdown = Thread({ closed.set(true); process.get()?.destroy() }, "psd2live-kde-picker-shutdown")
        owner?.addWindowListener(closeListener)
        if (ownerId != null) { activeOwner = owner; owner?.isEnabled = false }
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            Thread({
                var directory = fallback
                try {
                    directory = initialDirectory(initialPath)
                    selected.set(Result(directory, handled = false))
                    if (ownerId != null && !closed.get()) {
                        // --attach takes an X11 window ID. Use xcb only for this child, matching AWT/XWayland;
                        // Qt's native Wayland backend cannot attach to an X11 ID. The override is child-local;
                        // kdialog advertises Qt's --platform flag but rejects it when reparsing its raw arguments.
                        val child = ProcessBuilder(command(ownerId, directory, title, kind))
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .apply { environment()["QT_QPA_PLATFORM"] = "xcb" }.start()
                        process.set(child)
                        child.outputStream.close()
                        if (closed.get()) child.destroy()
                        val output = child.inputStream.bufferedReader().use { it.readText() }
                        selected.set(result(child.waitFor(), output, directory, kind))
                    }
                } catch (failure: Exception) {
                    System.err.println("KDE file picker unavailable; using Swing: ${failure.message}")
                    selected.set(Result(directory, handled = false))
                } finally {
                    if (closed.get()) selected.set(Result(directory, handled = true))
                    EventQueue.invokeLater { eventLoop.exit() }
                }
            }, "psd2live-kde-picker").apply { isDaemon = true }.start()
            eventLoop.enter()
            return selected.get()
        } finally {
            closed.set(true)
            process.get()?.takeIf { it.isAlive }?.destroy()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            owner?.removeWindowListener(closeListener)
            activeOwner = null
            if (owner?.isDisplayable == true && ownerId != null) {
                owner.isEnabled = wasEnabled
                owner.toFront()
                owner.requestFocus()
            }
        }
    }
}
