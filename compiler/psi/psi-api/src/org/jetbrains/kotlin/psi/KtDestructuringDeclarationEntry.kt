/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.typeRefHelpers.getTypeReference
import org.jetbrains.kotlin.psi.typeRefHelpers.setTypeReference

class KtDestructuringDeclarationEntry(node: ASTNode) : @Suppress("DEPRECATION") KtNamedDeclarationNotStubbed(node), KtVariableDeclaration {
    override val typeReference: KtTypeReference?
        get() = getTypeReference(this)

    override fun setTypeReference(typeRef: KtTypeReference?): KtTypeReference? {
        return setTypeReference(this, nameIdentifier, typeRef)
    }

    override val colon: PsiElement?
        get() = findChildByType<PsiElement>(KtTokens.COLON)

    override val valueParameterList: KtParameterList? = null

    override val valueParameters: List<KtParameter> get() = emptyList()

    override val receiverTypeReference: KtTypeReference? = null

    override fun getTypeParameterList(): KtTypeParameterList? = null

    override fun getTypeConstraintList(): KtTypeConstraintList? = null

    override fun getTypeConstraints(): List<KtTypeConstraint> = emptyList()

    override fun getTypeParameters(): List<KtTypeParameter> = emptyList()

    override fun <R, D> accept(visitor: KtVisitor<R, D>, data: D): R {
        return visitor.visitDestructuringDeclarationEntry(this, data)
    }

    override val isVar: Boolean
        get() {
            return findChildByType<PsiElement>(KtTokens.VAR_KEYWORD) != null ||
                    parentNode.findChildByType(KtTokens.VAR_KEYWORD) != null
        }

    override fun getInitializer(): KtNameReferenceExpression? {
        return PsiTreeUtil.getNextSiblingOfType<KtNameReferenceExpression>(
            findChildByType<PsiElement>(KtTokens.EQ),
            KtNameReferenceExpression::class.java
        )
    }

    override fun hasInitializer(): Boolean = initializer != null

    private val parentNode: ASTNode
        get() {
            val parent = node.treeParent
            assert(parent.elementType === KtNodeTypes.DESTRUCTURING_DECLARATION) { "parent is " + parent.elementType }
            return parent
        }

    override fun getValOrVarKeyword(): PsiElement? {
        val node = parentNode.findChildByType(KtTokens.VAL_VAR) ?: return null
        return node.psi
    }

    /**
     * Returns the PSI element for the val or var keyword of the entry itself or null.
     *
     * Only entries of full form destructuring declarations have their own val or var keyword.
     */
    val ownValOrVarKeyword: PsiElement?
        get() = findChildByType(KtTokens.VAL_VAR)

    override fun getFqName(): FqName? = null

    override fun getUseScope(): SearchScope {
        var enclosingBlock = KtPsiUtil.getEnclosingElementForLocalDeclaration(this, false)
        if (enclosingBlock is KtParameter) {
            enclosingBlock = KtPsiUtil.getEnclosingElementForLocalDeclaration(enclosingBlock, false)
        }
        if (enclosingBlock != null) return LocalSearchScope(enclosingBlock)

        return super.getUseScope()
    }
}
