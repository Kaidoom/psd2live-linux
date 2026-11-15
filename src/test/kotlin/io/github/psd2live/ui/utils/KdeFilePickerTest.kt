package io.github.psd2live.ui.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KdeFilePickerTest {
    @Test fun nativePickerIsLimitedToKdeLinuxAndCanBeDisabled() {
        assertTrue(KdeFilePicker.enabled("Linux", "KDE", false))
        assertTrue(KdeFilePicker.enabled("Linux", "something:KDE", false))
        assertFalse(KdeFilePicker.enabled("Linux", "GNOME", false))
        assertFalse(KdeFilePicker.enabled("Windows 11", "KDE", false))
        assertFalse(KdeFilePicker.enabled("Linux", "KDE", true))
    }

    @Test fun picturesUsesXdgLocationThenHomePicturesThenHome() {
        val home = Files.createTempDirectory("picker-home").toFile()
        try {
            val relocated = File(home, "Images with spaces 图片").apply { mkdir() }
            assertEquals(relocated, KdeFilePicker.picturesDirectory(home) { relocated.path })
            val pictures = File(home, "Pictures").apply { mkdir() }
            assertEquals(pictures, KdeFilePicker.picturesDirectory(home) { "/missing-pictures-directory" })
            assertEquals(pictures, KdeFilePicker.picturesDirectory(home) { error("No xdg-user-dir") })
            pictures.delete()
            assertEquals(home, KdeFilePicker.picturesDirectory(home) { null })
            assertEquals(home, KdeFilePicker.picturesDirectory(home) { "." })
        } finally { home.deleteRecursively() }
    }

    @Test fun commandKeepsArgumentsSeparateAndAttachesToTheAwtX11Window() {
        val directory = File("/tmp/Pictures with spaces")
        val command = KdeFilePicker.command(0x1234L, directory, "Open 'project'")
        assertEquals(listOf("kdialog", "--print-winid", "--attach", "0x1234", "--title", "Open 'project'",
            "--getopenfilename", directory.path, "PSD2Live (*.psd2live *.PSD2LIVE)"), command)
        val psd = KdeFilePicker.command(0x1234L, directory, "Reimport PSD", KdeFilePicker.Kind.PSD)
        assertEquals("Photoshop (*.psd *.PSD)", psd.last())
        assertEquals(directory.path, psd[psd.indexOf("--getopenfilename") + 1])
    }

    @Test fun reimportStartsBesideItsSourceAndFallsBackToPictures() {
        val home = Files.createTempDirectory("reimport-picker-home").toFile()
        try {
            val pictures = File(home, "Pictures").apply { mkdir() }
            val sourceDirectory = File(home, "素材 with spaces").apply { mkdir() }
            val source = File(sourceDirectory, "character.psd").apply { writeText("fixture") }
            assertEquals(sourceDirectory, KdeFilePicker.initialDirectory(source.path) { pictures })
            assertEquals(sourceDirectory, KdeFilePicker.initialDirectory(sourceDirectory.path) { pictures })
            source.delete()
            assertEquals(sourceDirectory, KdeFilePicker.initialDirectory(source.path) { pictures })
            sourceDirectory.delete()
            assertEquals(pictures, KdeFilePicker.initialDirectory(source.path) { pictures })
            assertEquals(pictures, KdeFilePicker.initialDirectory(null) { pictures })
            assertEquals(pictures, KdeFilePicker.initialDirectory("") { pictures })
            assertEquals(pictures, KdeFilePicker.initialDirectory("relative.psd") { pictures })
        } finally { home.deleteRecursively() }
    }

    @Test fun reimportAcceptsPsdFilesAndCancellationWithoutAcceptingProjects() {
        val directory = Files.createTempDirectory("kde-reimport-result").toFile()
        try {
            val psd = File(directory, " 模型 with spaces.PSD").apply { writeText("fixture") }
            val project = File(directory, "character.psd2live").apply { writeText("fixture") }
            fun result(code: Int, path: String = "") =
                KdeFilePicker.result(code, "winId: 1234\n$path\n", directory, KdeFilePicker.Kind.PSD)
            assertEquals(psd, result(0, psd.path).file)
            assertNull(result(0, project.path).file)
            assertNull(result(0, directory.path).file)
            assertNull(result(0, File(directory, "missing.psd").path).file)
            assertTrue(result(1).handled)
            assertNull(result(1).file)
            assertFalse(KdeFilePicker.result(1, "", directory, KdeFilePicker.Kind.PSD).handled)
            assertNull(KdeFilePicker.result(0, "winId: 1234\n${psd.path}\n", directory).file)
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationDoesNotFallBackAndSelectedPathsKeepWhitespaceAndUnicode() {
        val directory = Files.createTempDirectory("kde-picker-result").toFile()
        try {
            val project = File(directory, " 模型 with spaces.PSD2LIVE").apply { writeText("fixture") }
            val shown = "winId: 1234\n"
            val picked = KdeFilePicker.result(0, shown + project.path + "\n", directory)
            assertTrue(picked.handled)
            assertEquals(project, picked.file)
            assertEquals(project, KdeFilePicker.result(0, shown + "winId: 5678\n" + project.path + "\n", directory).file)
            val cancelled = KdeFilePicker.result(1, shown, directory)
            assertTrue(cancelled.handled)
            assertNull(cancelled.file)
            assertFalse(KdeFilePicker.result(255, "", directory).handled)
            assertFalse(KdeFilePicker.result(1, "", directory).handled)
            assertFalse(KdeFilePicker.result(2, "Qt could not start", directory).handled)
            assertNull(KdeFilePicker.result(0, shown + File(directory, "missing.psd2live").path, directory).file)
            assertNull(KdeFilePicker.result(0, shown + directory.path, directory).file)
            val wrong = File(directory, "wrong.txt").apply { writeText("fixture") }
            assertNull(KdeFilePicker.result(0, shown + wrong.path, directory).file)
        } finally { directory.deleteRecursively() }
    }
}
