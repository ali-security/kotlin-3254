/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.wasm.test.converters

import org.jetbrains.kotlin.backend.common.IrModuleInfo
import org.jetbrains.kotlin.backend.common.LoadedKlibs
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContextImpl
import org.jetbrains.kotlin.backend.common.linkage.partial.PartialLinkageConfig
import org.jetbrains.kotlin.backend.common.linkage.partial.PartialLinkageLogLevel
import org.jetbrains.kotlin.backend.common.linkage.partial.PartialLinkageMode
import org.jetbrains.kotlin.backend.common.linkage.partial.setupPartialLinkageConfig
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureDescriptor
import org.jetbrains.kotlin.backend.wasm.ic.IrFactoryImplForWasmIC
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.backend.js.MainModule
import org.jetbrains.kotlin.ir.backend.js.ModulesStructure
import org.jetbrains.kotlin.ir.backend.js.WholeWorldStageController
import org.jetbrains.kotlin.ir.backend.js.loadIr
import org.jetbrains.kotlin.ir.backend.js.loadIrForSingleModule
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsManglerDesc
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.isAnyPlatformStdlib
import org.jetbrains.kotlin.psi2ir.generators.TypeTranslatorImpl
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.frontend.classic.moduleDescriptorProvider
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.compilerConfigurationProvider
import org.jetbrains.kotlin.test.services.configuration.WasmEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.configuration.getDependencies
import org.jetbrains.kotlin.test.services.defaultsProvider
import org.jetbrains.kotlin.test.services.libraryProvider

enum class SingleModuleType {
    STDLIB, KOTLIN_TEST, TEST_MODULE
}

class WasmDeserializerSingleModuleFacade(testServices: TestServices, private val singleModuleType: SingleModuleType) :
    WasmDeserializerFacadeBase(testServices) {
    override fun loadKLibs(module: TestModule, moduleDescriptor: ModuleDescriptor): LoadedKlibs {
        val libraries = WasmEnvironmentConfigurator.getDependencyLibrariesFor(module, testServices)
        return when (singleModuleType) {
            SingleModuleType.STDLIB -> {
                val stdlib = libraries.single { it.isAnyPlatformStdlib }
                LoadedKlibs(
                    all = listOf(stdlib),
                    friends = emptyList(),
                    included = stdlib
                )
            }
            SingleModuleType.KOTLIN_TEST -> {
                check(libraries.size == 2) //stdlib and kotlin.test
                LoadedKlibs(
                    all = libraries,
                    friends = emptyList(),
                    included = libraries.last()
                )
            }
            SingleModuleType.TEST_MODULE -> {
                super.loadKLibs(module, moduleDescriptor)
            }
        }
    }

    override fun loadKLibsIr(moduleStructure: ModulesStructure): IrModuleInfo =
        loadIrForSingleModule(moduleStructure, IrFactoryImplForWasmIC(WholeWorldStageController()))
}

class WasmDeserializerFacade(testServices: TestServices) : WasmDeserializerFacadeBase(testServices) {
    override fun loadKLibsIr(moduleStructure: ModulesStructure): IrModuleInfo {
        return loadIr(
            modulesStructure = moduleStructure,
            irFactory = IrFactoryImplForWasmIC(WholeWorldStageController()),
            loadFunctionInterfacesIntoStdlib = true,
        )
    }
}

abstract class WasmDeserializerFacadeBase(
    testServices: TestServices,
) : DeserializerFacade<BinaryArtifacts.KLib, IrBackendInput>(testServices, ArtifactKinds.KLib, BackendKinds.IrBackend) {

    abstract fun loadKLibsIr(moduleStructure: ModulesStructure): IrModuleInfo

    override fun shouldTransform(module: TestModule): Boolean {
        require(testServices.defaultsProvider.backendKind == outputKind)
        return true
    }

    open fun loadKLibs(module: TestModule, moduleDescriptor: ModuleDescriptor): LoadedKlibs {
        val mainModuleLib: KotlinLibrary = testServices.libraryProvider.getCompiledLibraryByDescriptor(moduleDescriptor)
        val friendLibraries: List<KotlinLibrary> = getDependencies(module, testServices, DependencyRelation.FriendDependency)
            .map { testServices.libraryProvider.getCompiledLibraryByDescriptor(it) }

        return LoadedKlibs(
            all = WasmEnvironmentConfigurator.getDependencyLibrariesFor(module, testServices) + mainModuleLib,
            friends = friendLibraries,
            included = mainModuleLib
        )
    }

    override fun transform(module: TestModule, inputArtifact: BinaryArtifacts.KLib): IrBackendInput? {
        val configuration = testServices.compilerConfigurationProvider.getCompilerConfiguration(module)

        // Enforce PL with the ERROR log level to fail any tests where PL detected any incompatibilities.
        configuration.setupPartialLinkageConfig(PartialLinkageConfig(PartialLinkageMode.ENABLE, PartialLinkageLogLevel.ERROR))

        val moduleDescriptor = testServices.moduleDescriptorProvider.getModuleDescriptor(module)

        val mainModule = MainModule.Klib(inputArtifact.outputFile.absolutePath)
        val project = testServices.compilerConfigurationProvider.getProject(module)

        val klibs = loadKLibs(module, moduleDescriptor)

        val moduleStructure = ModulesStructure(
            project = project,
            mainModule = mainModule,
            compilerConfiguration = configuration,
            klibs = klibs,
        )

        val moduleInfo = loadKLibsIr(moduleStructure)

        val symbolTable = SymbolTable(IdSignatureDescriptor(JsManglerDesc), IrFactoryImplForWasmIC(WholeWorldStageController()))
        val pluginContext = IrPluginContextImpl(
            module = moduleDescriptor,
            bindingContext = BindingContext.EMPTY,
            languageVersionSettings = configuration.languageVersionSettings,
            st = symbolTable,
            typeTranslator = TypeTranslatorImpl(symbolTable, configuration.languageVersionSettings, moduleDescriptor),
            irBuiltIns = moduleInfo.bultins,
            linker = moduleInfo.deserializer,
            messageCollector = configuration.messageCollector,
        )
        return IrBackendInput.WasmDeserializedFromKlibBackendInput(
            moduleInfo,
            irPluginContext = pluginContext,
            klib = inputArtifact.outputFile,
        )
    }
}
