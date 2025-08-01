/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.psi

import com.intellij.psi.PsiElement

interface KtCallableDeclaration : KtNamedDeclaration, KtTypeParameterListOwner {
    val valueParameterList: KtParameterList?

    val valueParameters: List<KtParameter>

    val receiverTypeReference: KtTypeReference?

    val contextReceivers: List<KtContextReceiver>
        get() = emptyList()

    val typeReference: KtTypeReference?

    fun setTypeReference(typeRef: KtTypeReference?): KtTypeReference?

    val colon: PsiElement?
}
