package com.serranofp.alloy.semantics

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.stubs.AlloyNameIndex

/** Go to Symbol and Search Everywhere over the declarations in [AlloyNameIndex]. */
class AlloySymbolContributor : ChooseByNameContributorEx {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        StubIndex.getInstance().processAllKeys(AlloyNameIndex.KEY, processor, scope, filter)
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        StubIndex.getInstance().processElements(
            AlloyNameIndex.KEY, name, parameters.project, parameters.searchScope, parameters.idFilter,
            AlloyNamedDeclaration::class.java,
        ) { declaration -> (declaration as? NavigationItem)?.let(processor::process) ?: true }
    }
}
