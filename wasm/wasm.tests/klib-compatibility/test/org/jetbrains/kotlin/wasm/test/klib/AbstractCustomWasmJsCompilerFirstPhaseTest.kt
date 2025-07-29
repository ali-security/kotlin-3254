/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.wasm.test.klib

import org.jetbrains.kotlin.js.test.JsFailingTestSuppressor
import org.jetbrains.kotlin.js.test.fir.setupDefaultDirectivesForFirJsBoxTest
import org.jetbrains.kotlin.js.test.ir.AbstractJsBlackBoxCodegenTestBase.JsBackendFacades
import org.jetbrains.kotlin.js.test.ir.commonConfigurationForJsBackendSecondStageTest
import org.jetbrains.kotlin.js.test.ir.configureJsBoxHandlers
import org.jetbrains.kotlin.platform.wasm.WasmPlatforms
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.klib.CustomKlibCompilerFirstPhaseTestSuppressor
import org.jetbrains.kotlin.test.model.DependencyKind
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerWithTargetBackendTest
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.configuration.JsEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.sourceProviders.AdditionalDiagnosticsSourceFilesProvider
import org.jetbrains.kotlin.test.services.sourceProviders.CoroutineHelpersSourceFilesProvider
import org.jetbrains.kotlin.utils.bind
import org.jetbrains.kotlin.js.test.klib.CustomWebCompilerFirstPhaseEnvironmentConfigurator
import org.jetbrains.kotlin.js.test.klib.CustomWebCompilerFirstPhaseFacade
import org.jetbrains.kotlin.js.test.klib.customWasmJsCompilerSettings
import org.jetbrains.kotlin.js.test.klib.defaultLanguageVersion

open class AbstractCustomWasmJsCompilerFirstPhaseTest : AbstractKotlinCompilerWithTargetBackendTest(TargetBackend.WASM) {
    override fun configure(builder: TestConfigurationBuilder) = with(builder) {
        globalDefaults {
            // Note: Need to specify the concrete FE kind because this affects the choice of IGNORE_BACKEND_* directive.
            frontend = if (customWasmJsCompilerSettings.defaultLanguageVersion.usesK2) FrontendKinds.FIR else FrontendKinds.ClassicFrontend
            targetPlatform = WasmPlatforms.wasmJs
            dependencyKind = DependencyKind.Binary
        }

        useConfigurators(
            // These configurators are only necessary for the second compilation phase:
            ::CommonEnvironmentConfigurator,
            ::JsEnvironmentConfigurator,

            // And this configurator is necessary for the first (custom) compilation phase:
            ::CustomWebCompilerFirstPhaseEnvironmentConfigurator,
        )

        useAdditionalSourceProviders(
            ::CoroutineHelpersSourceFilesProvider,
            ::AdditionalDiagnosticsSourceFilesProvider,
        )

        facadeStep(::CustomWebCompilerFirstPhaseFacade)

        useAfterAnalysisCheckers(
            // Suppress all tests that have not been successfully compiled by the first phase.
            ::CustomKlibCompilerFirstPhaseTestSuppressor,

            // And all tests that failed on the second phase, but there is a ".fail" file.
            ::JsFailingTestSuppressor.bind(true),

            // ... or failed on the second phase, but they are anyway marked as "IGNORE_BACKEND*".
            ::BlackBoxCodegenSuppressor,
        )

        // TODO: review
        commonConfigurationForJsBackendSecondStageTest(
            pathToTestDir = "compiler/testData/codegen/box/",
            testGroupOutputDirPrefix = "customWasmJsCompilerFirstPhaseTest/",
            backendFacades = JsBackendFacades.WithRecompilation
        )

        // TODO: review
        setupDefaultDirectivesForFirJsBoxTest(parser = /* Does not matter */ FirParser.LightTree)

        // TODO: review
        configureJsBoxHandlers()
    }
}