/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.cases.components.typeCreator

import org.jetbrains.kotlin.analysis.api.components.KaClassTypeBuilder
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForDebug
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.analysis.test.framework.base.AbstractAnalysisApiBasedTest
import org.jetbrains.kotlin.analysis.test.framework.projectStructure.KtTestModule
import org.jetbrains.kotlin.analysis.test.framework.services.expressionMarkerProvider
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.types.Variance

abstract class AbstractBuildClassTypeTest : AbstractAnalysisApiBasedTest() {
    override val additionalDirectives: List<DirectivesContainer>
        get() = super.additionalDirectives + listOf(Directives)

    override fun doTestByMainFile(mainFile: KtFile, mainModule: KtTestModule, testServices: TestServices) {
        val actual = copyAwareAnalyzeForTest(mainFile) { contextFile ->
            val targetExpression = testServices.expressionMarkerProvider
                .getBottommostSelectedElementOfType(contextFile, KtExpression::class)
            val expressionType = targetExpression.expressionType ?: error("Expression type is null")
            val allTypesById = testServices.expressionMarkerProvider.getAllCarets(contextFile).associate { caret ->
                val qualifier = caret.qualifier
                val caretExpression =
                    testServices.expressionMarkerProvider.getBottommostElementOfTypeAtCaret<KtExpression>(contextFile, qualifier)
                val expressionType = caretExpression.expressionType ?: error("Expression under $qualifier doesn't have a type")
                val id = caret.qualifier.toIntOrNull() ?: error("Caret qualifier $qualifier is not a number")

                id to expressionType
            }
            val argumentDirectives = mainModule.testModule.directives[Directives.ARGUMENT]

            val isMarkedNullable = Directives.NULLABLE in mainModule.testModule.directives

            val builderConfiguration: KaClassTypeBuilder.() -> Unit = {
                this.isMarkedNullable = isMarkedNullable

                argumentDirectives.forEach { typeArgument ->
                    when (typeArgument) {
                        is TypeArgument.StarProjection -> argument(buildStarTypeProjection())
                        is TypeArgument.TypeArgumentWithVariance -> {
                            val type = allTypesById[typeArgument.caretId] ?: error("No type with id ${typeArgument.caretId}")
                            argument(type, typeArgument.variance)
                        }
                    }
                }
            }


            val symbol = expressionType.symbol ?: error("Expression type does not have a symbol")
            buildString {
                appendLine("CLASS_TYPE_BY_CLASS_ID")

                val classId = symbol.classId
                if (classId == null) {
                    appendLine("   ClassId is null")
                } else {
                    val classTypeByClassId = buildClassType(classId, builderConfiguration)

                    appendLine(
                        "   ${KaType::class.simpleName}: ${
                            classTypeByClassId.render(
                                renderer = KaTypeRendererForDebug.WITH_QUALIFIED_NAMES,
                                position = Variance.INVARIANT,
                            )
                        }"
                    )
                }

                val classTypeBySymbol = buildClassType(symbol, builderConfiguration)

                appendLine("CLASS_TYPE_BY_SYMBOL")
                appendLine(
                    "   ${KaType::class.simpleName}: ${
                        classTypeBySymbol.render(
                            renderer = KaTypeRendererForDebug.WITH_QUALIFIED_NAMES,
                            position = Variance.INVARIANT,
                        )
                    }"
                )
            }
        }

        testServices.assertions.assertEqualsToTestOutputFile(actual)
    }

    private sealed class TypeArgument {
        class StarProjection : TypeArgument()
        class TypeArgumentWithVariance(val variance: Variance, val caretId: Int) : TypeArgument()
    }

    private object Directives : SimpleDirectivesContainer() {
        val NULLABLE by directive("Make resulting type nullable")
        val ARGUMENT by valueDirective("Type argument to use for class creation") { string ->
            val splits = string.split("_")
            val variance = splits.first()
            val parsedVariance = when (variance.uppercase()) {
                "IN" -> Variance.IN_VARIANCE
                "OUT" -> Variance.OUT_VARIANCE
                "INV" -> Variance.INVARIANT
                "STAR" -> null
                else -> error("Unknown variance: $string")
            }

            if (parsedVariance == null) {
                return@valueDirective TypeArgument.StarProjection()
            }

            val id = splits.getOrNull(1)?.toIntOrNull() ?: error("Cannot parse caret id from $string")

            return@valueDirective TypeArgument.TypeArgumentWithVariance(parsedVariance, id)
        }
    }
}
