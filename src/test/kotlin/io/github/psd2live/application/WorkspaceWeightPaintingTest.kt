package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import io.github.psd2live.scenario.req
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.test.*

class WorkspaceWeightPaintingTest {
    @TempDir lateinit var temp: Path
    private class Fixture(val builder: WorkspacePreviewBuilder, val runtime: WorkspaceRuntime<RigPreviewModel>) {
        val now get() = runtime.capture()
        suspend fun paint(request: JsonObject) = now.let { WorkspaceDocumentCommands(runtime).execute(it.projectId, it.state,
            "Paint weights", listOf(WorkspaceDocumentOperation("vertex_group_paint", request)), MutationAuthor.USER) }
    }

    private suspend fun fixture(legacy: Boolean = false, migrated: Boolean = true,
                                gate: suspend () -> Unit = {}): Fixture {
        val source = SyntheticProject(temp.resolve("source-${legacy}-${migrated}"), 2, 12, 1).tree.head().snapshot
        val config = PipelineConfig(atlasSize = 64, meshSpacing = 8, generateDeformers = false, generatePhysics = false,
            rigEdits = RigEditOverlay(parameterEdits = listOf(RigParameterEdit("Input", "Input", -1f, 1f, 0f, created = true)),
                modifierStack = if (legacy) null else RigModifierStack().toJson()))
        var doc = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val builder = WorkspacePreviewBuilder()
        var model = builder.build(doc)
        val targets = model.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val weights = model.rig.puppet.drawables.flatMap { mesh -> listOf(VertexGroupKind.PIN, VertexGroupKind.MASS).map { kind ->
            VertexGroupJournal.encode(VertexGroup(kind.jsonName, mesh.id, kind,
                FloatArray(mesh.mesh!!.vertexCount) { (it % 4) * .2f }))
        } }
        if (legacy || !migrated) doc = doc.copy(rigEdits = doc.rigEdits.copy(authoringJournal = weights))
        else {
            val author = ModifierAuthorEdits.append(RigModifierStack(), targets)
            doc = doc.copy(rigEdits = doc.rigEdits.copy(modifierStack = author.withOverrides(author.instances.last().id, weights).toJson()))
        }
        model = builder.build(doc)
        if (!legacy) {
            val sim = RigSimEdit("cloth", "Cloth", SimKind.CLOTH, targets.map { it.id }, inputs = listOf(PhysicsInput("Input")))
            val counts = model.rig.puppet.drawables.associate { it.id.raw to it.mesh!!.vertexCount }
            val axis = SimBakedAxis("Mode", floatArrayOf(-1f, 0f, 1f), counts.mapValues { (_, n) ->
                listOf(FloatArray(n * 2) { -.1f }, FloatArray(n * 2), FloatArray(n * 2) { .1f }) })
            val baked = sim.copy(bake = SimBakeResult(SimBake.fingerprint(model.rig.puppet, sim), counts, emptyList(), listOf(SimBakedMode(axis, 1f, 1f))))
            val stack = RigModifierStack.fromJson(doc.rigEdits.modifierStack!!).add("simulation", 1, targets, baked.toJson(), id = "sim")
                .update("sim") { it.copy(inputFingerprint = RigModifierInput.simulationFingerprint(model.rig.modifierOutput!!, baked)) }
            doc = doc.copy(rigEdits = doc.rigEdits.copy(modifierStack = stack.toJson()))
            model = builder.build(doc)
            assertTrue(model.modifierEvaluation!!.entries.all { it.status == ModifierStatus.ACTIVE })
        }
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous ->
            gate(); builder.build(document, previous)
        })
        runtime.install(runtime.state.value.state, "weights", doc, model)
        return Fixture(builder, runtime)
    }

    private fun request(model: PuppetModel, points: List<CanvasBrushPoint>, kind: String = "pin", mode: String = "set",
                        radius: Float = 5f, shape: String = "circle", connected: Boolean = false): JsonObject {
        return req("edit" to mapOf("action" to "brush", "targets" to model.drawables.map { it.id.raw }, "kind" to kind,
            "mode" to mode, "strength" to .85f, "radius" to radius, "hardness" to 0f, "shape" to shape,
            "connected_only" to connected, "points" to points.map { listOf(it.x, it.y) }))
    }

    @Test fun migratedWeightsPersistWithoutBakingAndRoundTripUndoAndArchive() = roundTrip(false, true)
    @Test fun freshModifierWeightsPersistWithoutBaking() = roundTrip(false, false)
    @Test fun legacyWeightsKeepTheirImmediateCommitPath() = roundTrip(true, false)

    @Test fun mergedMeshKeepsPaintAndPreviousBakeUntilExplicitBake() = runBlocking {
        val f = fixture()
        val before = f.now
        val target = before.model.rig.puppet.drawables.first { it.mesh!!.vertexCount > 3 }
        val edge = org.umamo.edit.MeshTopology.uniqueEdges(target.mesh!!.indices).first()
        val vertices = listOf(edge.endpointLow, edge.endpointHigh)
        val merge = req("op" to "canvas_topology", "action" to "merge", "id" to target.id.raw, "vertices" to vertices)
        val expected = RigAuthoringJournal.apply(before.model.rig.puppet, merge)
        val commands = WorkspaceDocumentCommands(f.runtime)
        commands.executeJournal(before.projectId, before.state, "Join vertices", JsonArray(listOf(merge)), MutationAuthor.USER)
        assertEquals(expected.vertexGroups, f.now.model.rig.puppet.vertexGroups)
        val count = target.mesh!!.vertexCount - 1
        val surface = CanvasWeightAuthoring.surfaces(f.now.model.rig.puppet, listOf(target.id), emptyMap(), false).single()
        f.paint(req("auto_bake" to false, "edit" to mapOf("action" to "brush", "targets" to listOf(target.id.raw),
            "kind" to "pin", "mode" to "set", "radius" to 5, "strength" to 1, "points" to listOf(listOf(surface.points[0].x, surface.points[0].y)))))
        val painted = f.now
        val weights = painted.model.rig.puppet.vertexGroups.single { it.drawableId == target.id && it.kind == VertexGroupKind.PIN }.weights
        assertEquals(count, weights.size)
        assertEquals(1f, weights[0])
        val evaluation = painted.model.modifierEvaluation!!
        val input = evaluation.outputs[evaluation.stack.index("sim") - 1]
        assertEquals(target.mesh!!.vertexCount * 2, input.meshes.single { it.id == target.id.raw }.geometry!!.positions.size)
        assertEquals(ModifierStatus.ACTIVE, evaluation.entries.single { it.instance.id == "sim" }.status)
        commands.execute(painted.projectId, painted.state, "Bake", listOf(WorkspaceDocumentOperation("simulation_bake", req("id" to "sim"))), MutationAuthor.USER)
        val baked = RigSimEdit.fromJson(f.now.model.modifierEvaluation!!.stack.instances.single { it.id == "sim" }.config).bake
        assertEquals(count, assertNotNull(baked).vertexCounts.getValue(target.id.raw))
        assertContentEquals(weights, f.now.model.rig.puppet.vertexGroups.single { it.drawableId == target.id && it.kind == VertexGroupKind.PIN }.weights)
        assertEquals(painted.model.rig.puppet.vertexGroups, f.builder.build(painted.document).rig.puppet.vertexGroups)
    }

    private fun roundTrip(legacy: Boolean, migrated: Boolean): Unit = runBlocking {
        val f = fixture(legacy, migrated)
        val before = f.now
        val mesh = before.model.rig.puppet.drawables.first()
        val point = CanvasWeightAuthoring.surfaces(before.model.rig.puppet, listOf(mesh.id), emptyMap(), false).single().points.first()
        val stroke = request(before.model.rig.puppet, listOf(point))
        val expected = WorkspaceCanvasWeightEdits.commands(before.model.rig.puppet, WorkspaceDocumentOperation("vertex_group_paint", stroke))
            .map { it.jsonObject }.fold(before.model.rig.puppet, VertexGroupJournal::apply)
        RigBuildProfile.recording = true
        val measurement = try { RigBuildProfile.measureSuspending(RigBuildProfile.Operation("weights", before.revision, "paint", "test", 2, 0)) {
            f.paint(stroke)
        } } finally { RigBuildProfile.recording = false }
        assertFalse(measurement.stages.keys.any { "bake" in it }, "Painting must not enter simulation preparation or baking")
        val painted = f.now
        assertEquals(expected.vertexGroups, painted.model.rig.puppet.vertexGroups)
        assertEquals(before.historyHead, f.runtime.history().selections.single { it.node.id == painted.historyHead }.node.parentId)
        if (!legacy) {
            val old = RigModifierStack.fromJson(before.document.rigEdits.modifierStack!!).instances.single { it.id == "sim" }
            val next = RigModifierStack.fromJson(painted.document.rigEdits.modifierStack!!).instances.single { it.id == "sim" }
            assertEquals(old.config, next.config, "Painting must not rewrite simulation configuration or saved bakes")
            assertEquals(setOf("sim"), RigModifierStack.fromJson(painted.document.rigEdits.modifierStack!!).pendingWeightEdits.keys)
            assertEquals(old.inputFingerprint, next.inputFingerprint)
            assertTrue(next.enabled)
        }
        // Switching the active kind and refreshing cannot replace independent groups.
        f.paint(request(painted.model.rig.puppet, listOf(point), kind = "mass", mode = "subtract"))
        assertEquals(painted.model.rig.puppet.vertexGroups.filter { it.kind == VertexGroupKind.PIN },
            f.now.model.rig.puppet.vertexGroups.filter { it.kind == VertexGroupKind.PIN })
        val finished = f.now
        assertEquals(finished.model.rig.puppet.vertexGroups, f.builder.build(finished.document).rig.puppet.vertexGroups)
        f.runtime.checkout(finished.projectId, finished.state, before.historyHead)
        assertEquals(before.model.rig.puppet.vertexGroups, f.now.model.rig.puppet.vertexGroups)
        f.runtime.checkout(finished.projectId, f.now.state, finished.historyHead)
        assertEquals(finished.model.rig.puppet.vertexGroups, f.now.model.rig.puppet.vertexGroups)
        val project = SyntheticProject(temp.resolve("archive"), 1, 8, 1)
        val revision = WorkspaceRevisions.of(finished.document)
        project.tree.commit(project.tree.head().node.id, finished.document, revision, revision, "Weights", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val path = temp.resolve("weights.psd2live")
        ProjectRepository(writeHeadCache = false).save(project.capture(), path)
        ProjectRepository().open(path).use { opened ->
            assertEquals(finished.model.rig.puppet.vertexGroups, f.builder.build(opened.history.head().snapshot).rig.puppet.vertexGroups)
        }
    }

    @Test fun staleBackgroundStrokeCannotReplaceANewerProjectOrPreview(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var held = false
        val f = fixture(gate = { if (held) { entered.complete(Unit); release.await() } })
        val original = f.now
        held = true
        val pending = async(Dispatchers.Default) { runCatching { f.paint(request(original.model.rig.puppet, listOf(CanvasBrushPoint(0f, 0f)))) } }
        withTimeout(10_000) { entered.await() }
        f.runtime.install(f.now.state, "new-project", original.document, original.model)
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertEquals("new-project", f.now.projectId)
        assertEquals(original.model.rig.puppet.vertexGroups, f.now.model.rig.puppet.vertexGroups)
    }

    @Test fun legacyPendingWeightsSurviveLoadingAndCanBeBakedExplicitly(): Unit = runBlocking {
        val f = fixture()
        val overlay = f.now.document.rigEdits
        val sim = SimulationCatalog.entries(overlay).single()
        val pending = overlay.copy(modifierStack = JsonObject(overlay.modifierStack!! +
            ("pendingWeightEdits" to JsonArray(listOf(JsonPrimitive(sim.id))))))
        val restored = RigModifierStack.fromJson(pending.modifierStack!!)
        assertEquals(emptyList(), restored.pendingWeightEdits.getValue(sim.id))
        assertEquals(restored, RigModifierStack.fromJson(restored.toJson()))
        val loaded = f.builder.build(f.now.document.copy(rigEdits = pending))
        assertEquals(ModifierStatus.FAILED, loaded.modifierEvaluation!!.entries.single { it.instance.id == sim.id }.status)
        assertEquals(f.now.model.rig.puppet.vertexGroups, loaded.rig.puppet.vertexGroups)
        assertTrue(SimulationCatalog.stale(pending, f.now.model.rig.puppet, sim), "Bake all must include explicit upstream invalidation")
        f.runtime.install(f.now.state, "legacy-pending", f.now.document.copy(rigEdits = pending), loaded)
        val before = f.now
        WorkspaceDocumentCommands(f.runtime).execute(before.projectId, before.state, "Bake pending weights",
            listOf(WorkspaceDocumentOperation("simulation_bake", req("id" to sim.id))), MutationAuthor.USER)
        val current = f.now.model.modifierEvaluation!!
        assertEquals(ModifierStatus.ACTIVE, current.entries.single { it.instance.id == sim.id }.status)
        assertFalse(sim.id in current.stack.pendingWeightEdits)
        assertEquals(loaded.rig.puppet.vertexGroups, f.now.model.rig.puppet.vertexGroups)
    }

    @Test fun autoBakeIsAnExplicitAtomicOptIn(): Unit = runBlocking {
        val f = fixture()
        val before = f.now
        var baking = false
        val observer = object : WorkspaceRasterWork {
            override fun checkpoint() {}
            override fun progress(fraction: Float, message: String) {
                if (message.startsWith("Baking simulation")) { baking = true; throw CancellationException("Stop the opted-in bake") }
            }
        }
        val stroke = JsonObject(request(before.model.rig.puppet, listOf(CanvasBrushPoint(0f, 0f))) + ("auto_bake" to JsonPrimitive(true)))
        assertFailsWith<CancellationException> {
            WorkspaceDocumentCommands(f.runtime, rasterWork = observer).execute(before.projectId, before.state, "Paint and bake",
                listOf(WorkspaceDocumentOperation("vertex_group_paint", stroke)), MutationAuthor.USER)
        }
        assertTrue(baking)
        assertEquals(before.state, f.now.state)
        assertEquals(before.document, f.now.document)
    }

}
