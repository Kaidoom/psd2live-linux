package io.github.psd2live.scenario

import io.github.psd2live.application.*
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceSourceArt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** An older digest must run its saved worker, with the same inputs and output as its native source adapter. */
class ArchivedPresetScenario {
    @TempDir lateinit var temp: Path

    @Test fun archivedBindingsRetainSourceGeometryAndSharedParameters(): Unit = runBlocking {
        val mouth = Characters.closedMouthed()
        val eyes = Characters.eyed()
        val existing = mouth.source.layers.mapTo(HashSet()) { it.id }
        val character = mouth.copy(source = WorkspaceSourceArt(mouth.source.widthPx, mouth.source.heightPx,
            mouth.source.layers + eyes.source.layers.filter { it.id !in existing }, mouth.source.groups),
            layerOverrides = eyes.layerOverrides + mouth.layerOverrides)
        val (prepared, choices) = WorkspacePresetInitialize.prepare(character, WorkspaceInitialModifiers.RECOMMENDED)
        val document = WorkspacePresetInitialization.prepare(prepared, WorkspaceRasterWork.Direct, requireNotNull(choices),
            requestedVersions = mapOf("mouth/fitted" to 1, "eye/fitted" to 1, "body/legacy" to 1)).first
        val expected = RigIrCompiler.compile(WorkspacePreviewBuilder(modifierCachesEnabled = false).build(document))
        val stack = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack))
        val types = setOf("hair", "mouth/fitted", "eye.smile", "eye.emotion", "eye.gaze-lid", "eye.aftershock", "body.bust", "body.arms")
        assertTrue(stack.instances.map { it.type }.containsAll(types), "Exercise separate source adapters: ${stack.instances.map { it.type }}")
        val store = ModifierPluginStore.shared
        val installed = mutableListOf<ModifierPluginReference>()
        try {
            val archived = stack.copy(instances = stack.instances.map { instance ->
                if (instance.type !in types) return@map instance
                val artifact = requireNotNull(BundledAlgorithmArtifacts.find(instance.type, instance.version))
                // Same executable, a different immutable package identity. Hair keeps its historical manifest omission.
                val manifest = artifact.manifest.copy(name = "Archived regression fixture: ${instance.type}",
                    version = if (instance.type == "hair") "1.0.64" else artifact.manifest.version,
                    algorithms = artifact.manifest.algorithms.map {
                        if (instance.type == "hair") it.copy(requires = setOf("rig.scaffold")) else it
                    })
                val directory = Files.createDirectory(temp.resolve(instance.type.replace('/', '-')))
                Files.writeString(directory.resolve("plugin.json"), Json.encodeToString(ModifierPluginManifest.serializer(), manifest))
                for (file in manifest.files.keys) requireNotNull(artifact.open(file)).use {
                    Files.copy(it, directory.resolve(file))
                }
                val entry = store.install(directory.toString())
                installed += entry.reference
                instance.copy(plugin = entry.reference)
            })
            val saved = document.copy(rigEdits = document.rigEdits.copy(modifierStack = archived.toJson()))
            Studio.run(temp.resolve("workspace"), saved) {
                assertEquals(expected, RigIrCompiler.compile(now.model))
                assertEquals(archived, RigModifierStack.fromJson(requireNotNull(now.document.rigEdits.modifierStack)))
                reopen()
                assertEquals(expected, RigIrCompiler.compile(now.model))
                assertEquals(archived, RigModifierStack.fromJson(requireNotNull(now.document.rigEdits.modifierStack)))
            }
        } finally {
            installed.forEach(ModifierPluginWorkers::stop)
        }
    }
}
