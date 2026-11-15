package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceMaterialReimport
import io.github.psd2live.project.MaterialFolder
import io.github.psd2live.project.MaterialLibrary

internal sealed interface MaterialReimportPrompt {
    val filename: String
    data class Target(override val filename: String, val folders: List<MaterialFolder>, val library: MaterialLibrary,
                      val used: Set<String>) : MaterialReimportPrompt
    data class Layers(override val filename: String, val plan: WorkspaceMaterialReimport.Plan,
                      val library: MaterialLibrary) : MaterialReimportPrompt
}

internal sealed interface MaterialReimportDecision {
    data object Cancel : MaterialReimportDecision
    /** null explicitly imports a separate folder. */
    data class Target(val folderId: String?) : MaterialReimportDecision
    data class Layers(val choices: Map<Int, String>, val fitChangedBounds: Boolean) : MaterialReimportDecision
}
