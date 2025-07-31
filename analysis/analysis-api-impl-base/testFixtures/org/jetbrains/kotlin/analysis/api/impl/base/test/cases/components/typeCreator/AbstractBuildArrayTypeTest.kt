/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.cases.components.typeCreator

import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForDebug
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.test.framework.base.AbstractAnalysisApiBasedTest
import org.jetbrains.kotlin.analysis.test.framework.projectStructure.KtTestModule
import org.jetbrains.kotlin.analysis.test.framework.services.expressionMarkerProvider
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer
import org.jetbrains.kotlin.test.directives.model.singleOrZeroValue
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.types.Variance

abstract class AbstractBuildArrayTypeTest : AbstractAnalysisApiBasedTest() {
    override val additionalDirectives: List<DirectivesContainer>
        get() = super.additionalDirectives + listOf(Directives)

    override fun doTestByMainFile(mainFile: KtFile, mainModule: KtTestModule, testServices: TestServices) {
        val actual = copyAwareAnalyzeForTest(mainFile) { contextFile ->
            val targetExpression = testServices.expressionMarkerProvider
                .getBottommostSelectedElementOfType(contextFile, KtExpression::class)
            val expressionType = targetExpression.expressionType ?: error("Expression type is null")

            val isMarkedNullable = Directives.NULLABLE in mainModule.testModule.directives
            val variance = mainModule.testModule.directives.singleOrZeroValue(Directives.VARIANCE).parseVariance()
            val preferPrimitive = Directives.PREFER_PRIMITIVE in mainModule.testModule.directives

            val arrayType = buildArrayType(expressionType) {
                this.isMarkedNullable = isMarkedNullable
                this.variance = variance
                this.shouldPreferPrimitiveTypes = preferPrimitive
            }

            val varargArrayType = buildVarargArrayType(expressionType)

            buildString {
                appendLine("ARRAY_TYPE")
                appendLine(
                    "   ${KaType::class.simpleName}: ${
                        arrayType.render(
                            renderer = KaTypeRendererForDebug.WITH_QUALIFIED_NAMES,
                            position = Variance.INVARIANT,
                        )
                    }"
                )
                appendLine("VARARG_ARRAY_TYPE")
                appendLine(
                    "   ${KaType::class.simpleName}: ${
                        varargArrayType.render(
                            renderer = KaTypeRendererForDebug.WITH_QUALIFIED_NAMES,
                            position = Variance.INVARIANT,
                        )
                    }"
                )
            }
        }

        testServices.assertions.assertEqualsToTestOutputFile(actual)
    }

    private fun String?.parseVariance(): Variance = when (this?.uppercase()) {
        "IN" -> Variance.IN_VARIANCE
        "OUT" -> Variance.OUT_VARIANCE
        else -> Variance.INVARIANT
    }

    private object Directives : SimpleDirectivesContainer() {
        val PREFER_PRIMITIVE by directive("Prefer primitive type for array element")
        val NULLABLE by directive("Make resulting type nullable")
        val VARIANCE by stringDirective("Variance of resulting type")
    }
}