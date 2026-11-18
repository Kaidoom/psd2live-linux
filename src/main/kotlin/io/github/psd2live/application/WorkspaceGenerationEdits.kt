package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.SourceArt

/** Explicit semantic generation over the complete editable workspace, preserving existing mesh topology. */
internal object WorkspaceGenerationEdits {
    const val GENERATE = "rig_generate"

    fun generate(document: WorkspaceDocument, model: RigPreviewModel, refresh: Set<String>?, presetGroup: String = WorkspaceModifierPlan.RECOMMENDED_GROUP,
                 work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        require(presetGroup == "none" || presetGroup in WorkspaceModifierPlan.groups) { "Unknown preset group: $presetGroup" }
        if (document.rigEdits.modifierStack != null) return generatePresets(document, model, refresh, presetGroup, work)
        val layout = document.layout
        val generation = layout.generation
        if (!generation.generated) {
            // The first generation reads every layer as it is now.
            return seeded(document, model).copy(layout = layout.copy(generation = WorkspaceGeneration.Generated),
                layerOverrides = document.layerOverrides + generation.pendingClassifications, generationSource = null, meshSource = null)
        }
        // The reference layers stay out of every generation.
        val pending = WorkspaceLayoutPreview.pendingIds(document) - layout.referenceLayerIds
        val refreshed = (refresh ?: generation.refreshLayerIds).toSet()
        refreshed.forEach { id ->
            require(document.source.layers.any { it.id.raw == id } && id !in document.deletedLayerIds) { "Layer not found: $id" }
            require(id !in pending && id !in layout.referenceLayerIds) { "Layer $id has not been generated yet" }
        }
        if (pending.isEmpty() && generation.pendingClassifications.isEmpty() && refreshed.isEmpty()) return document
        // Absent from the frozen generation input, a layer generates from its current pixels.
        val seeded = refreshArtwork(seeded(document, model), refreshed)
        return seeded.copy(
            layerOverrides = document.layerOverrides + generation.pendingClassifications,
            generationSource = seeded.generationSource?.without(pending),
            meshSource = seeded.meshSource?.without(pending),
            layout = layout.copy(generation = generation.copy(pendingLayerIds = emptySet(), pendingClassifications = emptyMap(),
                refreshLayerIds = generation.refreshLayerIds - refreshed)),
        )
    }

    /** Explicit stacks keep authored meshes and add bindings only for targets not already captured by a structure. */
    private fun generatePresets(document: WorkspaceDocument, model: RigPreviewModel, refresh: Set<String>?, group: String,
                                work: WorkspaceRasterWork): WorkspaceDocument {
        val generation = document.layout.generation
        val pending = WorkspaceLayoutPreview.pendingIds(document) - document.layout.referenceLayerIds
        val refreshed = (refresh ?: generation.refreshLayerIds).toSet()
        refreshed.forEach { id ->
            require(document.source.layers.any { it.id.raw == id } && id !in document.deletedLayerIds) { "Layer not found: $id" }
            require(id !in pending && id !in document.layout.referenceLayerIds) { "Layer $id has not been generated yet" }
        }
        val seed = refreshArtwork(seeded(document, model), refreshed)
        val pins = model.stableLayerIdByDrawableId.entries.groupBy({ it.value }, { it.key })
            .filterValues { it.size == 1 }.mapValues { it.value.single() }
        val candidate = seed.copy(
            rigEdits = seed.rigEdits.copy(splitDrawableIds = pins + seed.rigEdits.splitDrawableIds),
            layerOverrides = seed.layerOverrides + generation.pendingClassifications,
            generationSource = seed.generationSource?.without(pending),
            meshSource = seed.meshSource?.without(pending),
            layout = seed.layout.copy(generation = WorkspaceGeneration.Generated.copy(
                refreshLayerIds = generation.refreshLayerIds - refreshed)),
        )
        if (group == "none") return candidate
        val stack = RigModifierStack.fromJson(requireNotNull(candidate.rigEdits.modifierStack))
        if (group == WorkspaceModifierPlan.RECOMMENDED_GROUP && stack.instances.isEmpty() && stack.appliedCapabilities.isEmpty())
            return WorkspacePresetSwitch.apply(candidate, "modern", work)
        val covered = (stack.instances.filter { it.type == RigSourceModifiers.SCAFFOLD }.flatMap { it.targets } +
            stack.appliedCapabilities.filter { it.type == RigSourceModifiers.SCAFFOLD }.flatMap { it.targets }).mapTo(HashSet()) { it.id }
        // Use the candidate's classifications and mesh seeds, not the stale pre-generation preview.
        val built = PSD2LivePipeline().buildPreview(candidate.source, candidate.config())
        val targets = built.rig.puppet.drawables.filter { it.mesh != null && it.id.raw !in covered &&
            built.rig.ownLayerIdByDrawableId[it.id.raw]?.let { layer -> layer !in candidate.layout.referenceLayerIds } == true }
        if (targets.isEmpty()) return candidate
        val next = WorkspaceModifierPlan.plan(built, stack, buildJsonObject {
            put("group", group)
            putJsonArray("targets") { targets.forEach { addJsonObject { put("kind", "mesh"); put("id", it.id.raw) } } }
        }).first
        return candidate.copy(rigEdits = candidate.rigEdits.copy(modifierStack = next.toJson()))
    }

    fun hasLiveSourceMesh(model: RigPreviewModel, layerId: String): Boolean {
        val evaluation = model.modifierEvaluation ?: return false
        val ids = model.rig.ownLayerIdByDrawableId.filterValues { it == layerId }.keys
        return evaluation.entries.any { entry ->
            entry.status == ModifierStatus.ACTIVE && entry.instance.type == RigSourceModifiers.SCAFFOLD &&
                entry.instance.id !in evaluation.stack.appliedNodes &&
                entry.instance.targets.any { it.id in ids }
        }
    }

    /** Refresh only these source layers, without generating other pending layers or changing preset selection. */
    fun refreshArtwork(document: WorkspaceDocument, layers: Set<String>): WorkspaceDocument {
        if (layers.isEmpty()) return document
        val visible = document.config().visiblePixelMeshLayers + layers
        val seeds = layers.intersect(RigMeshSeed.layers(document.rigEdits).keys)
        return document.copy(
            settings = JsonObject(document.settings + ("visiblePixelMeshLayers" to JsonArray(visible.sorted().map(::JsonPrimitive)))),
            generationSource = document.generationSource?.without(layers), meshSource = document.meshSource?.without(layers),
            rigEdits = document.rigEdits.copy(authoringJournal = document.rigEdits.authoringJournal + RigMeshSeed.refresh(seeds)),
            layout = document.layout.copy(generation = document.layout.generation.copy(
                refreshLayerIds = document.layout.generation.refreshLayerIds - layers)),
        )
    }

    /** A failed live modifier or a newly orphaned sculpt would otherwise be silently omitted from the preview. */
    fun validateRefresh(before: RigPreviewModel, after: RigPreviewModel) {
        val previous = before.modifierEvaluation?.entries?.associateBy { it.instance.id }.orEmpty()
        for (entry in after.modifierEvaluation?.entries.orEmpty()) {
            val old = previous[entry.instance.id] ?: continue
            if (old.status == ModifierStatus.ACTIVE) require(entry.status == ModifierStatus.ACTIVE) {
                "Cannot adapt the artwork while preserving ${entry.instance.type} (${entry.instance.id}): ${entry.error ?: entry.missingDependencies}. Keep the existing mesh or adjust the modifier first."
            }
            val lost = (entry.overrideIssues - old.overrideIssues.toSet()).firstOrNull()
            require(lost == null) { "Cannot adapt the artwork while preserving ${lost?.target} at ${lost?.key}. Keep the existing mesh or adjust that pose first." }
        }
    }

    private fun seeded(document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val pending = (WorkspaceLayoutPreview.pendingIds(document) + document.layout.generation.pendingClassifications.keys) - document.layout.referenceLayerIds
        val records = RigMeshSeed.capture(model, pending)
        return if (records.isEmpty()) document else document.copy(rigEdits = document.rigEdits.copy(
            authoringJournal = document.rigEdits.authoringJournal + records))
    }

    private fun SourceArt.without(ids: Set<String>): SourceArt =
        if (layers.none { it.id.raw in ids }) this else WorkspaceSourceArt(widthPx, heightPx, layers.filter { it.id.raw !in ids }, groups)

    /**
     * [document] after the image of generated layer [id] was replaced: the rig keeps its geometry, and the next
     * generation may read the new pixels instead ([WorkspaceGeneration.refreshLayerIds]).
     */
    fun replacedGenerated(document: WorkspaceDocument, id: String): WorkspaceDocument {
        val generation = document.layout.generation
        if (!generation.generated || id in generation.refreshLayerIds || id in WorkspaceLayoutPreview.pendingIds(document)) return document
        return document.copy(layout = document.layout.copy(generation = generation.copy(refreshLayerIds = generation.refreshLayerIds + id)))
    }

    val schema: JsonObject by lazy {
        buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("state") { put("type", "string"); put("minLength", 1) }
                putJsonObject("preset_group") {
                    put("type", "string"); put("enum", JsonArray((WorkspaceModifierPlan.groups.keys + "none").map(::JsonPrimitive)))
                    put("description", "Preset group for source meshes not yet captured by a binding structure. Default: modern-standard. none keeps editable meshes without adding presets.")
                }
                putJsonObject("refresh_geometry") {
                    put("type", "array"); put("maxItems", 1024); putJsonObject("items") { put("type", "string"); put("minLength", 1) }
                    put("description", "Generated layers to mesh again from their current pixels (their image was replaced since). Omitted: every layer whose image was replaced; [] keeps all geometry.")
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("state"))))
        }
    }

    const val DESCRIPTION = "Generate the rig, or generate it again. A project created empty for Layout is not generated until this runs; " +
        "afterwards, layers placed since the last generation, classification and tag changes, and replaced images whose geometry should refresh " +
        "wait for it (workspace_inspect scope=generation lists them). The generators then make the rig from the layers as they are now and " +
        "merge it onto the user's edits: what the user left follows the new output, the user's keyforms, warps, physics, swings, skeleton and " +
        "glue stay, existing meshes keep their topology, and what does not carry over cleanly is reported in quality.regeneration. " +
        "Editing and export are available before generation too. One history edit."
}
