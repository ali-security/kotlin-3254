/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.isExplicitParentOfResolvedQualifier
import org.jetbrains.kotlin.fir.analysis.checkers.type.FirInlineExposedLessVisibleTypeChecker
import org.jetbrains.kotlin.fir.declarations.fullyExpandedClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.utils.addToStdlib.applyIf

object FirInlineBodyResolvedQualifierChecker : FirResolvedQualifierChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirResolvedQualifier) {
        val inlineFunctionBodyContext = context.inlineFunctionBodyContext ?: return
        if (expression.isExplicitParentOfResolvedQualifier()) return
        val accessedClass = expression.symbol
            ?.applyIf(expression.resolvedToCompanionObject) {
                fullyExpandedClass(context.session)?.resolvedCompanionObjectSymbol
            }
            ?: return
        val source = expression.source ?: return

        if (accessedClass.isCompanion) {
            inlineFunctionBodyContext.checkAccessedDeclaration(
                source, expression, accessedClass,
            )
        }

        val parentElement = context.containingElements.elementAtOrNull(context.containingElements.size - 2)

        if (!expression.isNonExpressionReceiverOf(parentElement)) {
            FirInlineExposedLessVisibleTypeChecker.check(accessedClass.defaultType(), source, inlineFunctionBodyContext)
        }
    }

    /**
     * True when [parentElement] is a [FirQualifiedAccessExpression] and [this] is its explicit receiver without being
     * the dispatch or extension receiver, meaning the receiver is a qualifier that's not an expression.
     */
    private fun FirResolvedQualifier.isNonExpressionReceiverOf(parentElement: FirElement?): Boolean {
        return parentElement is FirQualifiedAccessExpression
                && parentElement.explicitReceiver == this
                && parentElement.extensionReceiver != this
                && parentElement.dispatchReceiver != this
    }
}
