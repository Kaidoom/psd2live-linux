package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.RigModifierStack
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

/** PSD2LIVE_TOOLS=1 PSD2LIVE_IMPORT_PSD=/path/to/sample.psd ./gradlew :test --tests '*PsdImportSampleTool'. */
class PsdImportSampleTool {
    @Test fun importAndInitializeLocalPsd(): Unit = runBlocking {
        requireTools()
        val path = Path.of(requireNotNull(System.getenv("PSD2LIVE_IMPORT_PSD"))).toAbsolutePath()
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val imported = WorkspaceSourceImporter(runtime).importPsd(path, null, runtime.state.value.state,
            modifiers = WorkspaceInitialModifiers.EMPTY).capture
        println("Imported ${path.fileName}: ${imported.document.source.layers.size} layers")
        imported.model.analysis.layers.filter { it.semantic.tag.name.startsWith("MOUTH") }.forEach {
            println("${it.source.name}: ${it.semantic.tag}")
        }
        val document = WorkspaceModifierEdits.apply(WorkspaceDocumentOperation("modifier_initialize",
            buildJsonObject { put("modifiers", "recommended") }), imported.document, imported.model)
        assertTrue(RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack)).instances.isNotEmpty())
        val rebuilt = builder.build(document)
        assertTrue(rebuilt.rig.puppet.drawables.isNotEmpty())
        println("Initialized and rebuilt ${path.fileName}: ${rebuilt.rig.puppet.drawables.size} drawables")
    }
}
