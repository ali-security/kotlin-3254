/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.symbolProviders.combined

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.resolve.providers.FirCompositeCachedSymbolNamesProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolNamesProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProviderInternals
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirCompositeSymbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * [LLCombinedPackageDelegationSymbolProvider] combines multiple [FirSymbolProvider]s by delegating to the appropriate
 * providers based on package names.
 *
 * Unlike [LLCombinedKotlinSymbolProvider], which delegates based on modules, this provider builds a map from package
 * [FqName]s to lists of symbol providers that can provide symbols for that package. Then, for each symbol request,
 * it queries only the relevant providers for the given package.
 *
 * If any provider's [FirSymbolNamesProvider.getPackageNames] returns `null`, [LLCombinedPackageDelegationSymbolProvider] cannot be used.
 * In that case, [merge] falls back to [FirCompositeSymbolProvider], which queries all providers individually.
 *
 * TODO: Document: No need to check `mayHaveTopLevel*` as the [providersByPackage] access covers the package name and the name check can be
 *  done in the individual symbol provider (which will usually just be a single symbol provider).
 *  No need for caches since the delegation is simple.
 *  The map is built in classpath order of the providers, so the lists will reflect the classpath order, preserving it.
 */
internal class LLCombinedPackageDelegationSymbolProvider private constructor(
    session: FirSession,
    override val providers: List<FirSymbolProvider>,
    private val providersByPackage: Map<FqName, List<FirSymbolProvider>>
) : LLCombinedSymbolProvider<FirSymbolProvider>(session) {
    override val symbolNamesProvider: FirSymbolNamesProvider = FirCompositeCachedSymbolNamesProvider.fromSymbolProviders(session, providers)

    override fun getClassLikeSymbolByClassId(classId: ClassId): FirClassLikeSymbol<*>? {
        val relevantProviders = providersByPackage[classId.packageFqName] ?: return null

        return relevantProviders.firstNotNullOfOrNull { it.getClassLikeSymbolByClassId(classId) }
    }

    @FirSymbolProviderInternals
    override fun getTopLevelCallableSymbolsTo(destination: MutableList<FirCallableSymbol<*>>, packageFqName: FqName, name: Name) {
        val relevantProviders = providersByPackage[packageFqName] ?: return

        relevantProviders.forEach { it.getTopLevelCallableSymbolsTo(destination, packageFqName, name) }
    }

    @FirSymbolProviderInternals
    override fun getTopLevelFunctionSymbolsTo(destination: MutableList<FirNamedFunctionSymbol>, packageFqName: FqName, name: Name) {
        val relevantProviders = providersByPackage[packageFqName] ?: return

        relevantProviders.forEach { it.getTopLevelFunctionSymbolsTo(destination, packageFqName, name) }
    }

    @FirSymbolProviderInternals
    override fun getTopLevelPropertySymbolsTo(destination: MutableList<FirPropertySymbol>, packageFqName: FqName, name: Name) {
        val relevantProviders = providersByPackage[packageFqName] ?: return

        relevantProviders.forEach { it.getTopLevelPropertySymbolsTo(destination, packageFqName, name) }
    }

    override fun hasPackage(fqName: FqName): Boolean {
        val relevantProviders = providersByPackage[fqName] ?: return false

        // We still have to query individual providers since the package sets from symbol names providers
        // may contain false positives, so the `fqName` being in `providersByPackage` doesn't prove that the package exists.
        return relevantProviders.any { it.hasPackage(fqName) }
    }

    override fun estimateSymbolCacheSize(): Long = 0

    companion object {
        fun merge(session: FirSession, providers: List<FirSymbolProvider>): FirSymbolProvider? =
            if (providers.size > 1) {
                val providersByPackage = buildPackageToProvidersMap(providers)
                if (providersByPackage != null) {
                    LLCombinedPackageDelegationSymbolProvider(session, providers, providersByPackage)
                } else {
                    FirCompositeSymbolProvider(session, providers)
                }
            } else providers.singleOrNull()

        /**
         * Builds the "package to providers" map. If any package set is `null`, the resulting map will be `null` as well, and we'll need to
         * fall back to querying all providers individually.
         */
        private fun buildPackageToProvidersMap(providers: List<FirSymbolProvider>): Map<FqName, List<FirSymbolProvider>>? =
            buildMap<FqName, MutableList<FirSymbolProvider>> {
                providers.forEach { provider ->
                    val packageNames = provider.symbolNamesProvider.getPackageNames() ?: return null
                    packageNames.forEach { packageName ->
                        // TODO (marco): Trim the list to size afterwards instead? Or use an immutable array?
                        // We start with an array list of capacity 1 since most packages will only be associated with a single provider.
                        getOrPut(FqName(packageName)) { ArrayList(1) }.add(provider)
                    }
                }
            }
    }
}
