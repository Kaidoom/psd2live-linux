package io.github.psd2live.ui.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KdeProjectPickerTest {
    @Test fun nativePickerIsLimitedToKdeLinuxAndCanBeDisabled() {
        assertTrue(KdeProjectPicker.enabled("Linux", "KDE", false))
        assertTrue(KdeProjectPicker.enabled("Linux", "something:KDE", false))
        assertFalse(KdeProjectPicker.enabled("Linux", "GNOME", false))
        assertFalse(KdeProjectPicker.enabled("Windows 11", "KDE", false))
        assertFalse(KdeProjectPicker.enabled("Linux", "KDE", true))
    }

    @Test fun picturesUsesXdgLocationThenHomePicturesThenHome() {
        val home = Files.createTempDirectory("picker-home").toFile()
        try {
            val relocated = File(home, "Images with spaces 图片").apply { mkdir() }
            assertEquals(relocated, KdeProjectPicker.picturesDirectory(home) { relocated.path })
            val pictures = File(home, "Pictures").apply { mkdir() }
            assertEquals(pictures, KdeProjectPicker.picturesDirectory(home) { "/missing-pictures-directory" })
            assertEquals(pictures, KdeProjectPicker.picturesDirectory(home) { error("No xdg-user-dir") })
            pictures.delete()
            assertEquals(home, KdeProjectPicker.picturesDirectory(home) { null })
            assertEquals(home, KdeProjectPicker.picturesDirectory(home) { "." })
        } finally { home.deleteRecursively() }
    }

    @Test fun commandKeepsArgumentsSeparateAndAttachesToTheAwtX11Window() {
        val directory = File("/tmp/Pictures with spaces")
        val command = KdeProjectPicker.command(0x1234L, directory, "Open 'project'")
        assertEquals(listOf("kdialog", "--print-winid", "--attach", "0x1234", "--title", "Open 'project'",
            "--getopenfilename", directory.path, "PSD2Live (*.psd2live *.PSD2LIVE)"), command)
    }

    @Test fun cancellationDoesNotFallBackAndSelectedPathsKeepWhitespaceAndUnicode() {
        val directory = Files.createTempDirectory("kde-picker-result").toFile()
        try {
            val project = File(directory, " 模型 with spaces.PSD2LIVE").apply { writeText("fixture") }
            val shown = "winId: 1234\n"
            val picked = KdeProjectPicker.result(0, shown + project.path + "\n", directory)
            assertTrue(picked.handled)
            assertEquals(project, picked.file)
            assertEquals(project, KdeProjectPicker.result(0, shown + "winId: 5678\n" + project.path + "\n", directory).file)
            val cancelled = KdeProjectPicker.result(1, shown, directory)
            assertTrue(cancelled.handled)
            assertNull(cancelled.file)
            assertFalse(KdeProjectPicker.result(255, "", directory).handled)
            assertFalse(KdeProjectPicker.result(1, "", directory).handled)
            assertFalse(KdeProjectPicker.result(2, "Qt could not start", directory).handled)
            assertNull(KdeProjectPicker.result(0, shown + File(directory, "missing.psd2live").path, directory).file)
            assertNull(KdeProjectPicker.result(0, shown + directory.path, directory).file)
            val wrong = File(directory, "wrong.txt").apply { writeText("fixture") }
            assertNull(KdeProjectPicker.result(0, shown + wrong.path, directory).file)
        } finally { directory.deleteRecursively() }
    }
}
