package io.github.psd2live.application

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*

/** Weight edits belong to the generated mesh's input stage, before the simulations that consume them. */
internal object WorkspaceVertexGroupEdits {
    private val operations = setOf(VertexGroupJournal.PUT, VertexGroupJournal.DELETE, VertexGroupJournal.RULE)
    fun onlyWeights(edits: JsonArray) = edits.isNotEmpty() && edits.all { it.jsonObject["op"]?.jsonPrimitive?.content in operations }
    fun isWeightOperation(operation: WorkspaceDocumentOperation): Boolean = operation.operation == "vertex_group_paint" ||
        operation.operation == "keyform_apply" && operation.request["changes"]?.jsonArray?.let(::onlyWeights) == true

    fun record(document: WorkspaceDocument, model: RigPreviewModel, edits: JsonArray): WorkspaceDocument {
        val commands = RigAuthoringJournal.compile(model.rig.puppet, edits).second
        if (commands.isEmpty()) return document
        val raw = document.rigEdits.modifierStack ?: return document.copy(rigEdits = document.rigEdits.copy(
            authoringJournal = document.rigEdits.authoringJournal + commands))
        var stack = RigModifierStack.fromJson(raw)
        val evaluation = requireNotNull(model.modifierEvaluation) { "Weights need the captured modifier evaluation" }
        val boundary = stack.instances.indexOfFirst { it.type == "simulation" && it.id !in stack.appliedNodes }.takeIf { it >= 0 } ?: stack.instances.size
        val input = if (boundary == 0) evaluation.authored else evaluation.outputs[boundary - 1]
        val targets = commands.mapTo(hashSetOf()) { it.getValue("target").jsonPrimitive.content.removePrefix("mesh:") }
        WorkspaceMeshPointEdits.simulationInput(model, targets)?.let { moved ->
            var placed = moved.stack
            var owner = moved.owner
            val index = placed.index(owner)
            if (!placed.instances[index].targets.map { it.id }.containsAll(targets)) {
                placed = insertWeightStage(placed, index + 1, targets)
                owner = placed.instances[index + 1].id
            }
            return withPendingWeights(document, placed.withOverrides(owner, placed.overrides[owner].orEmpty() + commands))
        }
        val shown = PuppetIr.toIr(model.rig.puppet)
        fun sameTopology(a: io.github.psd2live.format.model.RigIR) = targets.all { id ->
            val x = a.meshes.singleOrNull { it.id == id }?.geometry
            val y = shown.meshes.singleOrNull { it.id == id }?.geometry
            x != null && y != null && x.positions.size == y.positions.size && x.indices == y.indices && x.uvs == y.uvs
        }
        require(sameTopology(input)) { "Paint weights before a later topology modifier, where the simulation's vertex indices match the selected mesh" }
        for (index in boundary until stack.instances.size) {
            if (stack.instances[index].type == "simulation") continue
            val before = if (index == 0) evaluation.authored else evaluation.outputs[index - 1]
            val after = evaluation.outputs[index]
            require(before.authoring.vertexGroups.filter { it.mesh in targets } == after.authoring.vertexGroups.filter { it.mesh in targets }) {
                "Move the weight-edit stage before its simulation before painting these weights"
            }
        }
        // A migrated author stage may precede motion/physics entries. Reuse it only if no later writer
        // changes these meshes' topology or weights. Otherwise place a new ordinary author stage at the input.
        val existing = (0 until boundary).lastOrNull { index ->
            val entry = stack.instances[index]
            entry.type == ModifierAuthorEdits.TYPE && entry.id in stack.appliedNodes && entry.enabled &&
                sameTopology(evaluation.outputs[index]) &&
                evaluation.outputs[index].authoring.vertexGroups.filter { it.mesh in targets } == input.authoring.vertexGroups.filter { it.mesh in targets }
        }
        val owner = if (existing != null) stack.instances[existing].id else {
            stack = insertWeightStage(stack, boundary, input.meshes.map { it.id })
            stack.instances[boundary].id
        }
        stack = stack.withOverrides(owner, stack.overrides[owner].orEmpty() + commands)
        return withPendingWeights(document, stack)
    }

    private fun insertWeightStage(stack: RigModifierStack, index: Int, ids: Collection<String>): RigModifierStack {
        val appended = ModifierAuthorEdits.append(stack, ids.map { ModifierTarget("mesh", it) })
        return appended.copy(instances = stack.instances.take(index) + appended.instances.last() + stack.instances.drop(index))
    }

    private fun withPendingWeights(document: WorkspaceDocument, stack: RigModifierStack): WorkspaceDocument {
        // Defer evaluation of saved simulations without rewriting their configuration, deleting a
        // bake, or disabling the body. Explicit Bake clears this editor-side invalidation marker.
        val pending = stack.copy(pendingWeightEdits = stack.pendingWeightEdits + stack.instances.filter { it.type == "simulation" && it.id !in stack.appliedNodes }.map { it.id })
        return document.copy(rigEdits = document.rigEdits.copy(modifierStack = pending.toJson()))
    }
}
