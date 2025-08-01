/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.psi

import com.intellij.psi.PsiElement

interface KtDeclarationWithBody : KtDeclaration {
    val bodyExpression: KtExpression?

    val equalsToken: PsiElement?

    override fun getName(): String?

    val contractDescription: KtContractEffectList?
        get() = null

    fun hasContractEffectList(): Boolean {
        return contractDescription != null
    }

    /**
     * Whether the declaration may have a contract.
     *
     * `false` means that the declaration is definitely having no contract,
     * but `true` doesn't guarantee that the declaration has a contract.
     */
    fun mayHaveContract(): Boolean {
        return false
    }

    fun hasBlockBody(): Boolean

    fun hasBody(): Boolean

    fun hasDeclaredReturnType(): Boolean

    val valueParameters: List<KtParameter>

    val bodyBlockExpression: KtBlockExpression?
        get() = bodyExpression as? KtBlockExpression
}