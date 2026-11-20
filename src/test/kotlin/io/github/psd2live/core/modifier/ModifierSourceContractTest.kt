package io.github.psd2live.core.modifier

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.test.*

class ModifierSourceContractTest {
    @TempDir lateinit var temp: Path

    @Test fun bundledSourcePresetsMatchTheirHostAdapters() {
        val sourceTypes = RigSourceModifiers.definitions(null).mapTo(HashSet()) { it.type to it.version }
        for (entry in BuiltinModifierPlugins.entries) {
            val definition = entry.definition
            if ((definition.type to definition.version) !in sourceTypes) continue
            val algorithm = requireNotNull(entry.artifact).manifest.algorithms.single {
                it.version == definition.version && (it.type == definition.type || definition.type in it.aliases)
            }
            assertEquals(definition.requires, algorithm.requires, "${definition.type}: requires")
            assertEquals(definition.provides, algorithm.provides, "${definition.type}: provides")
            assertEquals(definition.exclusiveFamily, algorithm.exclusiveFamily, "${definition.type}: exclusiveFamily")
            assertEquals(definition.composableKinds, algorithm.composableKinds, "${definition.type}: composableKinds")
        }
    }

    private fun archivedHair() = requireNotNull(BundledAlgorithmArtifacts.find("hair", 1)).let { artifact ->
        InstalledModifierPlugin(artifact.manifest.copy(version = "1.0.64", algorithms = artifact.manifest.algorithms.map {
            it.copy(requires = setOf("rig.scaffold"))
        }), artifact.reference.copy(version = "1.0.64", digest = "f".repeat(64)), temp, true)
    }

    private fun adapter(entry: InstalledModifierPlugin): RigModifierDefinition {
        val captured = BuiltinModifierPlugins.definitions(null).single { it.type == "hair" && it.version == 1 }
            .copy(parameters = { emptyList() }, providers = { emptyMap() })
        val store = ModifierPluginStore(temp.resolve("store"), Preferences.userRoot().node("test/source-contract"))
        return ModifierPluginCatalog.definitions(store, listOf(captured), references = setOf(entry.reference),
            overlayPackages = listOf(entry)).single { it.type == "hair" }
    }

    @Test fun historicalHairRetainsItsExactWorkerAndHostDependencyChecks() {
        val entry = archivedHair()
        val definition = adapter(entry)
        val target = ModifierTarget("mesh", "hair-mesh")
        val hair = RigModifierInstance("hair", "hair", 1, listOf(target), plugin = entry.reference)
        assertTrue(definition.parameters(hair).isEmpty()) // Exercises the real catalog contract gate.
        assertEquals(entry.reference, definition.pluginReference)
        assertEquals(setOf("rig.scaffold", "body/legacy", "face/legacy"), definition.requires)
        val providers = listOf("rig.scaffold", "body/legacy", "face/legacy").map {
            RigModifierDefinition(it, 1, plugin = ModifierAlgorithmPlugin { model, _, _ -> model })
        }
        val evaluator = RigModifierEvaluator(RigModifierRegistry(listOf(definition.copy(pluginReference = null)) + providers))
        val misplaced = RigModifierStack(listOf(hair.copy(plugin = null), RigModifierInstance("body", "body/legacy", 1, listOf(target))))
        assertTrue(assertFailsWith<IllegalArgumentException> { evaluator.validateOrder(misplaced) }
            .message.orEmpty().contains("must follow body/legacy"))
    }

    @Test fun compatibilityDoesNotAcceptOtherContractChangesOrUnknownPackageVersions() {
        val original = archivedHair()
        val algorithm = original.manifest.algorithms.single()
        val invalid = listOf(
            original.copy(reference = original.reference.copy(version = "2.0.0")),
            original.copy(manifest = original.manifest.copy(algorithms = listOf(algorithm.copy(requires = emptySet())))),
            original.copy(manifest = original.manifest.copy(algorithms = listOf(algorithm.copy(provides = emptySet())))),
            original.copy(manifest = original.manifest.copy(algorithms = listOf(algorithm.copy(exclusiveFamily = "other")))),
            original.copy(manifest = original.manifest.copy(algorithms = listOf(algorithm.copy(composableKinds = emptySet())))),
        )
        for (entry in invalid) {
            val definition = adapter(entry)
            val failure = assertFailsWith<IllegalArgumentException> {
                definition.parameters(RigModifierInstance("hair", "hair", 1, listOf(ModifierTarget("mesh", "hair-mesh")), plugin = entry.reference))
            }
            assertTrue(failure.message.orEmpty().contains("Source adapter contract mismatch"), failure.message)
        }
    }
}
