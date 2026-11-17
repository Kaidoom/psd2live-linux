package io.github.psd2live.core.modifier

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BuiltinSourceContextCompatibilityTest {
    @TempDir lateinit var temp: Path

    @Test fun archivedWorkerWithoutTheNewSourceContextKeepsItsStrictOldSchema() {
        val artifact = requireNotNull(BundledAlgorithmArtifacts.find("mouth/fitted", 1))
        val directory = Files.createDirectory(temp.resolve("old-package"))
        // This fixture exercises capability detection only; no code in this jar is loaded or run.
        val runtime = directory.resolve("runtime.jar")
        ZipOutputStream(Files.newOutputStream(runtime)).use { zip ->
            zip.putNextEntry(ZipEntry("io/github/psd2live/algorithms/binding/MouthFittedSourceAlgorithm.class"))
            zip.write("older mouth input schema".toByteArray()); zip.closeEntry()
        }
        val manifest = artifact.manifest.copy(name = "Strict old mouth context fixture",
            files = mapOf("runtime.jar" to ModifierPluginStore.hash(runtime)))
        Files.writeString(directory.resolve("plugin.json"), Json.encodeToString(ModifierPluginManifest.serializer(), manifest))
        val store = ModifierPluginStore(temp.resolve("store"), Preferences.userRoot().node("test/old-mouth-source"))
        val entry = store.install(directory.toString())
        val instance = RigModifierInstance("mouth", "mouth/fitted", 1, listOf(ModifierTarget("mesh", "mouth")), plugin = entry.reference)
        assertFalse(BuiltinSourceContextCompatibility.supportsMouthScaffold(instance, store))
        assertFalse(BuiltinSourceContextCompatibility.supportsMouthRibbons(instance, store))
        assertFalse(BuiltinSourceContextCompatibility.supportsChainLabels(instance, store))
        assertTrue(BuiltinSourceContextCompatibility.supportsMouthScaffold(instance.copy(plugin = artifact.reference), store))
        assertTrue(BuiltinSourceContextCompatibility.supportsMouthRibbons(instance.copy(plugin = artifact.reference), store))
        assertTrue(BuiltinSourceContextCompatibility.supportsChainLabels(instance.copy(plugin = artifact.reference), store))
    }
}
