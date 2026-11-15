package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspaceMaterialReimport
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.MaterialReimportDecision
import io.github.psd2live.ui.state.MaterialReimportPrompt
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/** Only uncertain matches interrupt importing; no document changes precede this decision. */
@Composable
internal fun MaterialReimportDialog(prompt: MaterialReimportPrompt, decide: (MaterialReimportDecision) -> Unit) {
    val dismiss = { decide(MaterialReimportDecision.Cancel) }
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    when (prompt) {
        is MaterialReimportPrompt.Target -> {
            var selected by remember(prompt) { mutableStateOf<String?>(null) }
            ModalDialogFrame(title = tr("materials.reimport.title"), subtitle = prompt.filename, width = 600.dp, onDismiss = dismiss,
                footer = {
                    CompactButton(tr("action.cancel"), dismiss)
                    CompactButton(tr("materials.reimport.continue"), {
                        decide(MaterialReimportDecision.Target(selected?.takeUnless { it == WorkspaceMaterialReimport.NEW }))
                    }, enabled = selected != null, isPrimary = true)
                }) {
                ModalMessage(tr("materials.reimport.targetHint"))
                CompactDropdown(items = listOf(null, WorkspaceMaterialReimport.NEW) + prompt.folders.map { it.id },
                    selectedItem = selected, onItemSelected = { selected = it }, modifier = Modifier.fillMaxWidth(), itemLabel = { id ->
                        when (id) {
                            null -> tr("materials.reimport.choose")
                            WorkspaceMaterialReimport.NEW -> tr("materials.reimport.separate")
                            else -> {
                                val path = prompt.library.ancestry(id).reversed().joinToString(" / ") { it.name }
                                val used = WorkspaceMaterialReimport.sourceItems(prompt.library, id).count { it.id in prompt.used }
                                tr("materials.reimport.folder", path, used, id.takeLast(6))
                            }
                        }
                    })
                selected?.let(prompt.library::folder)?.sourcePath?.takeIf { it.isNotEmpty() }?.let { ModalMessage(it) }
            }
        }
        is MaterialReimportPrompt.Layers -> {
            val plan = prompt.plan
            val choices = remember(prompt) { mutableStateMapOf<Int, String>().apply {
                plan.issues.forEach { issue -> issue.suggested?.let { put(issue.index, it) } }
            } }
            var fit by remember(prompt) { mutableStateOf(false) }
            val valid = runCatching { plan.resolve(choices, fit) }.isSuccess
            val needsFit = choices.any { (index, id) -> plan.items.find { it.id == id }?.let {
                id in plan.used && WorkspaceMaterialReimport.changedBounds(it, plan.entries[index])
            } == true }
            val available = remember(prompt) { plan.items.filter { it.id !in plan.automatic.values } }
            ModalDialogFrame(title = tr("materials.reimport.title"), subtitle = prompt.filename, width = 680.dp, onDismiss = dismiss,
                footer = {
                    CompactButton(tr("action.cancel"), dismiss)
                    CompactButton(tr("materials.reimport.apply"), { decide(MaterialReimportDecision.Layers(choices.toMap(), fit)) },
                        enabled = valid, isPrimary = true)
                }) {
                ModalMessage(tr("materials.reimport.matchHint", plan.automatic.size, plan.issues.size))
                for (issue in plan.issues) key(issue.index) {
                    val entry = plan.entries[issue.index]
                    val selected = choices[issue.index]
                    val item = plan.items.find { it.id == selected }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MaterialThumbnail(entry.raster, 96, Modifier.size(48.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text((entry.sourceLayerPath ?: (WorkspaceMaterialReimport.relativeFolders(entry) + entry.name)).joinToString(" / "),
                                style = typography.body, color = colors.textPrimary)
                            Text(tr("materials.reimport.reason.${issue.reason.name.lowercase()}"), style = typography.caption, color = colors.textMuted)
                            CompactDropdown(items = listOf(null, WorkspaceMaterialReimport.NEW, WorkspaceMaterialReimport.SKIP) +
                                available.sortedBy { if (it.id in issue.candidates) 0 else 1 }.map { it.id },
                                selectedItem = selected, onItemSelected = { if (it == null) choices.remove(issue.index) else choices[issue.index] = it },
                                modifier = Modifier.fillMaxWidth(),
                                itemEnabled = { id -> id == null || id == WorkspaceMaterialReimport.NEW || id == WorkspaceMaterialReimport.SKIP ||
                                    choices.none { (index, value) -> index != issue.index && value == id } },
                                itemLabel = { id -> when (id) {
                                    null -> tr("materials.reimport.choose")
                                    WorkspaceMaterialReimport.NEW -> tr("materials.reimport.new")
                                    WorkspaceMaterialReimport.SKIP -> tr("materials.reimport.skip")
                                    else -> available.first { it.id == id }.let { old ->
                                        val path = prompt.library.ancestry(old.folderId).reversed().joinToString(" / ") { it.name }
                                        "$path / ${old.name} [${old.id.takeLast(6)}]" }
                                } })
                            if (item != null && WorkspaceMaterialReimport.changedBounds(item, entry))
                                Text(tr("materials.reimport.bounds", item.raster.width, item.raster.height, entry.raster.width, entry.raster.height),
                                    style = typography.caption, color = colors.warning)
                        }
                        if (item != null) MaterialThumbnail(item.raster, 96, Modifier.size(48.dp))
                    }
                }
                if (needsFit) {
                    CompactCheckbox(fit, { fit = it }, label = tr("materials.reimport.fit"))
                    ModalMessage(tr("materials.reimport.fitHint"), color = colors.warning)
                }
                ModalMessage(tr("materials.reimport.keepMissing"))
            }
        }
    }
}
