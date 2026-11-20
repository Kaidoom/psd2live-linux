package io.github.psd2live.tools

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.core.modifier.ClosedMouthVisibility
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.scenario.Studio
import io.github.psd2live.scenario.req
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.prefs.Preferences
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** Rebuilds an extracted project without saving or migrating the input archive. */
class ProjectLoadProbeTool {
    @TempDir lateinit var temp: Path
    @Test fun loadSavedProject(): Unit = runBlocking {
        requireTools()
        val archive = System.getenv("PSD2LIVE_PLUGIN_ARCHIVE")?.let(Path::of)
        if (archive != null) Preferences.userRoot().node("io.github.psd2live.settings/algorithm-plugins")
            .put("directory", temp.resolve("plugins").toString())
        val path = Path.of(requireNotNull(System.getenv("PSD2LIVE_LOAD_PROJECT")))
        fun hash() = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        val original = hash()
        try {
            ProjectRepository().open(path).use { opened ->
                val document = opened.history.head().snapshot
                val stack = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack))
                if (archive != null) for (reference in stack.instances.mapNotNull { it.plugin }.distinct()) {
                    val directory = archive.resolve(reference.digest)
                    if (Files.isDirectory(directory)) {
                        // Reinstall immutable payloads in the test store; never bring user installation locks.
                        val staged = Files.createDirectory(temp.resolve(reference.digest))
                        val manifest = Json.decodeFromString<ModifierPluginManifest>(Files.readString(directory.resolve("plugin.json")))
                        for (file in manifest.files.keys + "plugin.json") {
                            Files.createDirectories(staged.resolve(file).parent)
                            Files.copy(directory.resolve(file), staged.resolve(file))
                        }
                        val installed = ModifierPluginStore.shared.install(staged.toString())
                        assertEquals(reference, installed.reference)
                    }
                }
                val adapters = BuiltinModifierPlugins.definitions(null)
                for (instance in stack.instances.filter { it.enabled }) {
                    val reference = instance.plugin ?: continue
                    if (reference == BuiltinModifierPlugins.reference(instance.type, instance.version)) continue
                    val entry = requireNotNull(ModifierPluginStore.shared.findRunnable(reference)) {
                        "Missing saved package: ${instance.id} ${instance.type} $reference"
                    }
                    val algorithm = entry.manifest.algorithms.single { instance.type == it.type || instance.type in it.aliases }
                    val adapter = adapters.singleOrNull { it.type == instance.type && it.version == instance.version } ?: continue
                    fun compare(name: String, host: Any?, saved: Any?) {
                        if (host != saved) println("CONTRACT ${instance.id} ${instance.type}: $name host=$host saved=$saved")
                    }
                    compare("requires", adapter.requires, algorithm.requires)
                    compare("provides", adapter.provides, algorithm.provides)
                    compare("exclusiveFamily", adapter.exclusiveFamily, algorithm.exclusiveFamily)
                    compare("composableKinds", adapter.composableKinds, algorithm.composableKinds)
                }
                val model = WorkspacePreviewBuilder(modifierCachesEnabled = false).build(document)
                val missing = model.rig.modifierReport?.get("entries")?.jsonArray.orEmpty().filter {
                    val status = it.jsonObject["status"]?.jsonPrimitive?.content.orEmpty()
                    status.startsWith("missing") || status == "failed"
                }
                kotlin.test.assertTrue(missing.isEmpty(), "Saved modifiers must actually run: $missing")
                val issues = model.rig.modifierReport?.get("entries")?.jsonArray.orEmpty()
                    .flatMap { it.jsonObject["override_issues"]?.jsonArray.orEmpty() }
                println("Generated override issues: $issues")
                println("Opened ${path.fileName}: ${model.rig.puppet.drawables.size} drawables, ${stack.instances.size} modifiers")
                System.getenv("PSD2LIVE_WRITE_BASELINE")?.let { target ->
                    Files.write(Path.of(target), RigIrBinary.encode(RigIrCompiler.compile(model)))
                }
                System.getenv("PSD2LIVE_COMPARE_BASELINE")?.let { target ->
                    val expected = RigIrBinary.decode(Files.readAllBytes(Path.of(target)))
                    val actual = RigIrCompiler.compile(model)
                    val changed = expected.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                        .filter { field -> field.isAccessible = true; field.get(expected) != field.get(actual) }.map { it.name }
                    assertTrue(expected == actual, "Saved project output changed across the application update: $changed; " +
                        "missing meshes=${expected.meshes.map { it.id } - actual.meshes.map { it.id }.toSet()}")
                    println("Saved project output matches the pre-update baseline")
                }
                if (System.getenv("PSD2LIVE_TEST_VISIBILITY") == "1") {
                    Studio.run(temp.resolve("visibility"), document, checks = setOf(Studio.Check.UNDO, Studio.Check.REPLAY)) {
                        val before = puppet
                        val curve = ClosedMouthVisibility.Default.move(.05f, .25f).insert(.2f).setOpacity(.2f, 1f)
                        val mouth = stack.instances.single { it.enabled && MouthVisibilityCustomization.supports(it) }
                        assertNotEquals(BuiltinModifierPlugins.reference(mouth.type, mouth.version), mouth.plugin,
                            "Exercise the real saved worker")
                        edit("customize archived mouth visibility", "modifier_update", req("id" to mouth.id,
                            "config" to JsonObject(mouth.config + (ClosedMouthVisibility.CONFIG to curve.toJson()))))
                        val evaluator = CpuDeformationEvaluator()
                        val closed = now.model.analysis.layers.filter { it.semantic.tag == SemanticTag.MOUTH_CLOSE }
                            .mapTo(HashSet()) { it.source.id.raw }
                        val ids = now.model.rig.ownLayerIdByDrawableId.filterValues { it in closed }.keys
                        assertTrue(ids.isNotEmpty())
                        for (value in listOf(0f, .05f, .2f, .225f, .25f, .5f, 1f)) {
                            val pose = mapOf(ClosedMouthVisibility.PARAMETER to value)
                            val oldGeometry = evaluator.evaluate(before, pose).worldPositions
                            val newGeometry = evaluator.evaluate(puppet, pose).worldPositions
                            assertEquals(oldGeometry.keys, newGeometry.keys)
                            oldGeometry.forEach { (id, vertices) -> assertContentEquals(vertices, newGeometry.getValue(id), "Geometry $id at $value") }
                            for (id in ids) {
                                val drawable = puppet.drawables.single { it.id.raw == id }
                                val alpha = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity,
                                    { pose[it] ?: puppet.parameters.single { p -> p.id == it }.default })
                                assertEquals(curve.sample(value), alpha, .0001f, "Closed visibility $id at $value")
                            }
                        }
                        val expected = RigIrCompiler.compile(now.model)
                        reopen()
                        assertEquals(expected, RigIrCompiler.compile(now.model))
                        val result = RigModifierStack.fromJson(requireNotNull(now.document.rigEdits.modifierStack))
                        assertEquals(stack.instances.map { it.plugin }, result.instances.map { it.plugin })
                        assertEquals(curve, ClosedMouthVisibility.fromConfig(result.instances.single { it.id == mouth.id }.config))
                        System.getenv("PSD2LIVE_VISIBILITY_COPY")?.let { destination ->
                            Files.copy(save(), Path.of(destination), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                        println("Archived mouth visibility: edited, geometry unchanged, undo/redo and save/reopen verified")
                    }
                }
            }
        } finally { assertContentEquals(original, hash(), "The input project must remain unchanged") }
    }
}
