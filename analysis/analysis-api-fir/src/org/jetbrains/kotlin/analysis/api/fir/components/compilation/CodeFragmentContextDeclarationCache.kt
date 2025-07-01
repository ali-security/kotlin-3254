/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.components.compilation

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmEvaluatorData
import org.jetbrains.kotlin.fir.backend.Fir2IrCommonMemberStorage
import org.jetbrains.kotlin.fir.backend.Fir2IrScopeCache
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrMetadataSourceOwner
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.psi
import org.jetbrains.kotlin.psi.KtCodeFragment
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

/**
 * A cache for data to be passed between from the context file to the [KtCodeFragment].
 *
 * @param contextDeclaration A non-local declaration containing the context [PsiElement] of a code fragment.
 */
internal class CodeFragmentContextDeclarationCache(
    private val contextDeclaration: KtDeclaration,
    private val firstNonInlineNonLocalFunInStack: KtDeclaration?,
    private val selfSymbols: Set<FirBasedSymbol<*>>,
) {
    /** A list of scope caches we accumulated when compiling the code fragment context. */
    private val collectedLocalScopes = mutableListOf<Fir2IrScopeCache>()

    /**
     * Registers declarations from the scope [cache] if the specified [symbol] is in the local scope of the [contextDeclaration].
     * It means this function only stores local declarations that are accessible from the code fragment.
     */
    fun registerLocalScope(symbol: IrSymbol, cache: Fir2IrScopeCache) {
        if (cache.isEmpty()) {
            return
        }

        val declaration = symbol.owner as? IrDeclaration ?: return

        /** Check if we are inside [contextDeclaration] or [firstNonInlineNonLocalFunInStack]
         * Local functions from both of them might be captured by the code fragment
         */
        val shouldBeCollected = generateSequence(declaration) { it.parent as? IrDeclaration }
            .filterIsInstance<IrMetadataSourceOwner>()
            .any {
                val psi = it.metadata?.source?.psi
                psi == contextDeclaration || psi == firstNonInlineNonLocalFunInStack
            }

        if (shouldBeCollected) {
            /** [Fir2IrScopeCache.clone] here is necessary as the scope cache is cleaned up before popping. */
            collectedLocalScopes.add(cache.copyFilteringOutSelfSymbols())
        }
    }

    private fun Fir2IrScopeCache.copyFilteringOutSelfSymbols(): Fir2IrScopeCache {
        return Fir2IrScopeCache(
            filterOutSelfSymbols(parameters),
            filterOutSelfSymbols(variables),
            filterOutSelfSymbols(localFunctions),
            filterOutSelfSymbols(delegatedProperties)
        )
    }

    private fun <T : FirDeclaration, R> filterOutSelfSymbols(cache: Map<T, R>): Map<T, R> {
        return cache.filterKeys { !selfSymbols.contains(it.symbol) }
    }

    /**
     * Mapping between initial and desugared representations of local functions from the context module.
     * Currently, the map contains all local functions from the context module (including those outside the [contextDeclaration]).
     */
    var localDeclarationsData: JvmBackendContext.SharedLocalDeclarationsData? = null
        private set

    /**
     * A cache with declarations passed from the context module for the code fragment one.
     */
    var customCommonMemberStorage: Fir2IrCommonMemberStorage? = null
        private set

    fun initialize(commonMemberStorage: Fir2IrCommonMemberStorage, evaluatorData: JvmEvaluatorData?) {
        require(localDeclarationsData == null && customCommonMemberStorage == null) { "Cache is already initialized" }

        localDeclarationsData = evaluatorData?.localDeclarationsData

        customCommonMemberStorage = Fir2IrCommonMemberStorage().apply {
            classCache.putAll(filterOutSelfSymbols(commonMemberStorage.classCache))
            notFoundClassCache.putAll(commonMemberStorage.notFoundClassCache)
            typeParameterCache.putAll(filterOutSelfSymbols(commonMemberStorage.typeParameterCache))
            enumEntryCache.putAll(filterOutSelfSymbols(commonMemberStorage.enumEntryCache))
            localClassCache.putAll(filterOutSelfSymbols(commonMemberStorage.localClassCache))
            localCallableCache.addAll(commonMemberStorage.localCallableCache.map { it.copyFilteringOutSelfSymbols() })
            functionCache.putAll(filterOutSelfSymbols(commonMemberStorage.functionCache))
            dataClassGeneratedFunctionsCache.putAll(filterOutSelfSymbols(commonMemberStorage.dataClassGeneratedFunctionsCache))
            constructorCache.putAll(filterOutSelfSymbols(commonMemberStorage.constructorCache))
            propertyCache.putAll(filterOutSelfSymbols(commonMemberStorage.propertyCache))
            syntheticPropertyCache.putAll(commonMemberStorage.syntheticPropertyCache.filterKeys { !selfSymbols.contains(it.originalFunction.symbol) })
            getterForPropertyCache.putAll(commonMemberStorage.getterForPropertyCache)
            setterForPropertyCache.putAll(commonMemberStorage.setterForPropertyCache)
            backingFieldForPropertyCache.putAll(commonMemberStorage.backingFieldForPropertyCache)
            delegateVariableForPropertyCache.putAll(commonMemberStorage.delegateVariableForPropertyCache)
            irForFirSessionDependantDeclarationMap.putAll(commonMemberStorage.irForFirSessionDependantDeclarationMap.filterKeys {
                !selfSymbols.contains(
                    it.originalSymbol
                )
            })
            delegatedClassesInfo.putAll(commonMemberStorage.delegatedClassesInfo)
            firClassesWithInheritanceByDelegation.addAll(commonMemberStorage.firClassesWithInheritanceByDelegation.filter {
                !selfSymbols.contains(
                    it.symbol
                )
            })
            generatedDataValueClassSyntheticFunctions.putAll(commonMemberStorage.generatedDataValueClassSyntheticFunctions)

            localCallableCache.addAll(collectedLocalScopes.map { it.copyFilteringOutSelfSymbols() })
        }
    }
}