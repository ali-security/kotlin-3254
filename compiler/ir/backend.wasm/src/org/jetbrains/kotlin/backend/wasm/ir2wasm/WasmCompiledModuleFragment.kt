/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.ir2wasm

import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.backend.wasm.ir2wasm.WasmCompiledModuleFragment.*
import org.jetbrains.kotlin.ir.backend.js.ic.IrICProgramFragment
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrExternalPackageFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.wasm.ir.*
import org.jetbrains.kotlin.wasm.ir.WasmFunction
import org.jetbrains.kotlin.wasm.ir.source.location.SourceLocation
import kotlin.collections.mutableListOf

class BuiltinIdSignatures(
    val throwable: IdSignature?,
    val kotlinAny: IdSignature?,
    val jsToKotlinAnyAdapter: IdSignature?,
    val unitGetInstance: IdSignature?,
    val runRootSuites: IdSignature?,
    val registerModuleDescriptor: IdSignature?,
    val createString: IdSignature?,
)

class SpecialITableTypes(
    val wasmAnyArrayType: WasmSymbol<WasmArrayDeclaration> = WasmSymbol<WasmArrayDeclaration>(),
    val specialSlotITableType: WasmSymbol<WasmStructDeclaration> = WasmSymbol<WasmStructDeclaration>(),
)

class RttiGlobal(
    val global: WasmGlobal,
    val classSignature: IdSignature,
    val superClassSignature: IdSignature?,
)

class RttiElements(
    val globals: MutableList<RttiGlobal> = mutableListOf(),
    val globalReferences: ReferencableElements<IdSignature, WasmGlobal> = ReferencableElements(),
    val rttiType: WasmSymbol<WasmStructDeclaration> = WasmSymbol()
)

class WasmCompiledFileFragment(
    val fragmentTag: String?,
    val functions: ReferencableAndDefinable<IdSignature, WasmFunction> = ReferencableAndDefinable(),
    val globalFields: ReferencableAndDefinable<IdSignature, WasmGlobal> = ReferencableAndDefinable(),
    val globalVTables: ReferencableAndDefinable<IdSignature, WasmGlobal> = ReferencableAndDefinable(),
    val globalClassITables: ReferencableAndDefinable<IdSignature, WasmGlobal> = ReferencableAndDefinable(),
    val functionTypes: ReferencableAndDefinable<IdSignature, WasmFunctionType> = ReferencableAndDefinable(),
    val gcTypes: ReferencableAndDefinable<IdSignature, WasmTypeDeclaration> = ReferencableAndDefinable(),
    val vTableGcTypes: ReferencableAndDefinable<IdSignature, WasmTypeDeclaration> = ReferencableAndDefinable(),
    val stringLiteralAddress: ReferencableElements<String, Int> = ReferencableElements(),
    val stringLiteralPoolId: ReferencableElements<String, Int> = ReferencableElements(),
    val constantArrayDataSegmentId: ReferencableElements<Pair<List<Long>, WasmType>, Int> = ReferencableElements(),
    val jsFuns: MutableMap<IdSignature, JsCodeSnippet> = mutableMapOf(),
    val jsModuleImports: MutableMap<IdSignature, String> = mutableMapOf(),
    val exports: MutableList<WasmExport<*>> = mutableListOf(),
    var createStringLiteral: WasmSymbol<WasmFunction>? = null,
    var createStringLiteralType: WasmSymbol<WasmFunctionType>? = null,
    val mainFunctionWrappers: MutableList<IdSignature> = mutableListOf(),
    var testFunctionDeclarators: MutableList<IdSignature> = mutableListOf(),
    val equivalentFunctions: MutableList<Pair<String, IdSignature>> = mutableListOf(),
    val jsModuleAndQualifierReferences: MutableSet<JsModuleAndQualifierReference> = mutableSetOf(),
    val classAssociatedObjectsInstanceGetters: MutableList<ClassAssociatedObjects> = mutableListOf(),
    var builtinIdSignatures: BuiltinIdSignatures? = null,
    var specialITableTypes: SpecialITableTypes? = null,
    var rttiElements: RttiElements? = null,
    val objectInstanceFieldInitializers: MutableList<IdSignature> = mutableListOf(),
    val nonConstantFieldInitializers: MutableList<IdSignature> = mutableListOf(),
) : IrICProgramFragment()

class WasmCompiledModuleFragment(
    private val wasmCompiledFileFragments: List<WasmCompiledFileFragment>,
    private val generateTrapsInsteadOfExceptions: Boolean,
    private val isWasmJsTarget: Boolean
) {
    // Used during linking
    private val serviceCodeLocation = SourceLocation.NoLocation("Generated service code")
    private val parameterlessNoReturnFunctionType = WasmFunctionType(emptyList(), emptyList())

    private inline fun tryFindBuiltInFunction(select: (BuiltinIdSignatures) -> IdSignature?): WasmFunction? {
        for (fragment in wasmCompiledFileFragments) {
            val builtinSignatures = fragment.builtinIdSignatures ?: continue
            val signature = select(builtinSignatures) ?: continue
            return fragment.functions.defined[signature]
        }
        return null
    }

    private inline fun tryFindBuiltInType(select: (BuiltinIdSignatures) -> IdSignature?): WasmTypeDeclaration? {
        for (fragment in wasmCompiledFileFragments) {
            val builtinSignatures = fragment.builtinIdSignatures ?: continue
            val signature = select(builtinSignatures) ?: continue
            return fragment.gcTypes.defined[signature]
        }
        return null
    }

    class JsCodeSnippet(val importName: WasmSymbol<String>, val jsCode: String)

    open class ReferencableElements<Ir, Wasm : Any>(
        val unbound: MutableMap<Ir, WasmSymbol<Wasm>> = mutableMapOf()
    ) {
        fun reference(ir: Ir): WasmSymbol<Wasm> {
            val declaration = (ir as? IrSymbol)?.owner as? IrDeclarationWithName
            if (declaration != null) {
                val packageFragment = declaration.getPackageFragment()
                if (packageFragment is IrExternalPackageFragment) {
                    compilationException("Referencing declaration without package fragment", declaration)
                }
            }
            return unbound.getOrPut(ir) { WasmSymbol() }
        }
    }

    class ReferencableAndDefinable<Ir, Wasm : Any>(
        unbound: MutableMap<Ir, WasmSymbol<Wasm>> = mutableMapOf(),
        val defined: LinkedHashMap<Ir, Wasm> = LinkedHashMap(),
        val elements: MutableList<Wasm> = mutableListOf(),
        val wasmToIr: MutableMap<Wasm, Ir> = mutableMapOf()
    ) : ReferencableElements<Ir, Wasm>(unbound) {
        fun define(ir: Ir, wasm: Wasm) {
            if (ir in defined)
                compilationException("Trying to redefine element: IR: $ir Wasm: $wasm", type = null)

            elements += wasm
            defined[ir] = wasm
            wasmToIr[wasm] = ir
        }
    }

    private fun partitionDefinedAndImportedFunctions(): Pair<MutableList<WasmFunction.Defined>, MutableList<WasmFunction.Imported>> {
        val definedFunctions = mutableListOf<WasmFunction.Defined>()
        val importedFunctions = mutableListOf<WasmFunction.Imported>()
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.functions.elements.forEach { function ->
                when (function) {
                    is WasmFunction.Defined -> definedFunctions.add(function)
                    is WasmFunction.Imported -> importedFunctions.add(function)
                }
            }
        }
        return definedFunctions to importedFunctions
    }

    private fun createAndExportServiceFunctions(
        definedFunctions: MutableList<WasmFunction.Defined>,
        exports: MutableList<WasmExport<*>>,
        initializeUnit: Boolean,
        stringPoolSize: Int,
        wasmElements: MutableList<WasmElement>,
        additionalTypes: MutableList<WasmTypeDeclaration>,
        globals: MutableList<WasmGlobal>,
    ) {
        val fieldInitializerFunction = createFieldInitializerFunction()
        definedFunctions.add(fieldInitializerFunction)

        val associatedObjectGetter = createAssociatedObjectGetterFunction(wasmElements, additionalTypes)
        definedFunctions.add(associatedObjectGetter)

        val masterInitFunction = createAndExportMasterInitFunction(fieldInitializerFunction, associatedObjectGetter, initializeUnit)
        exports.add(WasmExport.Function("_initialize", masterInitFunction))
        definedFunctions.add(masterInitFunction)

        val createStringFunction = tryFindBuiltInFunction { it.createString }
            ?: compilationException("kotlin.createString is not file in fragments", null)
        val stringType = createStringFunction.type.owner.resultTypes[0]
        val (stringPoolField, stringArrayType) = createStringPoolField(stringPoolSize, stringType, additionalTypes)
        globals.add(stringPoolField)

        val stringLiteralFunction = createStringLiteralFunction(createStringFunction, stringPoolField, additionalTypes, wasmElements, stringArrayType)
        definedFunctions.add(stringLiteralFunction)

        val startUnitTestsFunction = createStartUnitTestsFunction()
        if (startUnitTestsFunction != null) {
            exports.add(WasmExport.Function("startUnitTests", startUnitTestsFunction))
            definedFunctions.add(startUnitTestsFunction)
        }
    }

    fun linkWasmCompiledFragments(moduleImportName: String?, initializeUnit: Boolean): WasmModule {
        // TODO: Implement optimal ir linkage KT-71040
        bindUnboundSymbols()
        val canonicalFunctionTypes = bindUnboundFunctionTypes()

        val data = mutableListOf<WasmData>()
        val stringPoolSize = bindStringPoolSymbolsAndGetSize(data)
        bindConstantArrayDataSegmentIds(data)

        val (definedFunctions, importedFunctions) = partitionDefinedAndImportedFunctions()

        val exports = mutableListOf<WasmExport<*>>()
        wasmCompiledFileFragments.flatMapTo(exports) { it.exports }

        val memories = createAndExportMemory(exports, moduleImportName)
        val (importedMemories, definedMemories) = memories.partition { it.importPair != null }

        val additionalTypes = mutableListOf<WasmTypeDeclaration>()
        additionalTypes.add(parameterlessNoReturnFunctionType)

        val syntheticTypes = mutableListOf<WasmTypeDeclaration>()
        createAndBindSpecialITableTypes(syntheticTypes)
        val globals = getGlobals(syntheticTypes)

        val elements = mutableListOf<WasmElement>()
        createAndExportServiceFunctions(definedFunctions, exports, initializeUnit, stringPoolSize, elements, additionalTypes, globals)

        val tags = getTags()
        require(tags.size <= 1) { "Having more than 1 tag is not supported" }

        val (importedTags, definedTags) = tags.partition { it.importPair != null }

        tags.forEach { additionalTypes.add(it.type) }

        val (importedGlobals, definedGlobals) = globals.partition { it.importPair != null }

        val importsInOrder = mutableListOf<WasmNamedModuleField>()
        importsInOrder.addAll(importedFunctions)
        importsInOrder.addAll(importedTags)
        importsInOrder.addAll(importedGlobals)
        importsInOrder.addAll(importedMemories)

        val recursiveTypeGroups = getTypes(syntheticTypes, canonicalFunctionTypes, additionalTypes)

        return WasmModule(
            recGroups = recursiveTypeGroups,
            importsInOrder = importsInOrder,
            importedFunctions = importedFunctions,
            importedMemories = importedMemories,
            definedFunctions = definedFunctions,
            importedTags = importedTags,
            tables = emptyList(),
            memories = definedMemories,
            globals = definedGlobals,
            importedGlobals = importedGlobals,
            exports = exports,
            startFunction = null,  // Module is initialized via export call
            elements = elements,
            data = data,
            dataCount = true,
            tags = definedTags
        ).apply { calculateIds() }
    }

    private fun createRttiTypeAndProcessRttiGlobals(globals: MutableList<WasmGlobal>, additionalTypes: MutableList<WasmTypeDeclaration>) {
        val wasmLongArray = WasmArrayDeclaration("LongArray", WasmStructFieldDeclaration("Long", WasmI64, false))
        additionalTypes.add(wasmLongArray)

        val rttiTypeDeclarationSymbol = WasmSymbol<WasmStructDeclaration>()
        val rttiTypeDeclaration = WasmStructDeclaration(
            name = "RTTI",
            fields = listOf(
                WasmStructFieldDeclaration("implementedIFaceIds", WasmRefNullType(WasmHeapType.Type(WasmSymbol(wasmLongArray))), false),
                WasmStructFieldDeclaration("superClassRtti", WasmRefNullType(WasmHeapType.Type(rttiTypeDeclarationSymbol)), false),
                WasmStructFieldDeclaration("packageNameAddress", WasmI32, false),
                WasmStructFieldDeclaration("packageNameLength", WasmI32, false),
                WasmStructFieldDeclaration("packageNamePoolId", WasmI32, false),
                WasmStructFieldDeclaration("simpleNameAddress", WasmI32, false),
                WasmStructFieldDeclaration("simpleNameLength", WasmI32, false),
                WasmStructFieldDeclaration("simpleNamePoolId", WasmI32, false),
                WasmStructFieldDeclaration("klassId", WasmI64, false),
                WasmStructFieldDeclaration("typeInfoFlag", WasmI32, false),
                WasmStructFieldDeclaration("createString", WasmFuncRef, false),
            ),
            superType = null,
            isFinal = true
        )
        rttiTypeDeclarationSymbol.bind(rttiTypeDeclaration)
        additionalTypes.add(rttiTypeDeclaration)

        val rttiGlobals = mutableMapOf<IdSignature, RttiGlobal>()
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.rttiElements?.globals?.forEach { global ->
                rttiGlobals[global.classSignature] = global
            }
        }

        wasmCompiledFileFragments.forEach { fragment ->
            fragment.rttiElements?.run {
                globalReferences.unbound.forEach { unbound ->
                    unbound.value.bind(rttiGlobals[unbound.key]?.global ?: error("A RttiGlobal was not found for ${unbound.key}"))
                }
                rttiType.bind(rttiTypeDeclaration)
            }
        }

        fun wasmRttiGlobalOrderKey(rttiGlobal: RttiGlobal?): Int =
            rttiGlobal?.superClassSignature?.let { wasmRttiGlobalOrderKey(rttiGlobals[it]) + 1 } ?: 0

        rttiGlobals.values.sortedBy(::wasmRttiGlobalOrderKey).mapTo(globals) { it.global }
    }

    private fun createAndBindSpecialITableTypes(syntheticTypes: MutableList<WasmTypeDeclaration>): MutableList<WasmTypeDeclaration> {
        val wasmAnyArrayType = WasmArrayDeclaration(
            name = "AnyArray",
            field = WasmStructFieldDeclaration("", WasmRefNullType(WasmHeapType.Simple.Any), false)
        )
        syntheticTypes.add(wasmAnyArrayType)

        val specialSlotITableTypeSlots = mutableListOf<WasmStructFieldDeclaration>()
        val wasmAnyRefStructField = WasmStructFieldDeclaration("", WasmAnyRef, false)
        repeat(WasmBackendContext.SPECIAL_INTERFACE_TABLE_SIZE) {
            specialSlotITableTypeSlots.add(wasmAnyRefStructField)
        }
        specialSlotITableTypeSlots.add(
            WasmStructFieldDeclaration(
                name = "",
                type = WasmRefNullType(WasmHeapType.Type(WasmSymbol(wasmAnyArrayType))),
                isMutable = false
            )
        )
        val specialSlotITableType = WasmStructDeclaration(
            name = "SpecialITable",
            fields = specialSlotITableTypeSlots,
            superType = null,
            isFinal = true
        )
        syntheticTypes.add(specialSlotITableType)

        wasmCompiledFileFragments.forEach { fragment ->
            fragment.specialITableTypes?.let { specialITableTypes ->
                specialITableTypes.wasmAnyArrayType.bind(wasmAnyArrayType)
                specialITableTypes.specialSlotITableType.bind(specialSlotITableType)
            }
        }

        return syntheticTypes
    }

    private fun getTags(): List<WasmTag> {
        if (generateTrapsInsteadOfExceptions) return emptyList()

        val tag = if (isWasmJsTarget) {
            val jsExceptionTagFuncType = WasmFunctionType(
                parameterTypes = listOf(WasmExternRef),
                resultTypes = emptyList()
            )

            WasmTag(jsExceptionTagFuncType, WasmImportDescriptor("intrinsics", WasmSymbol("tag")))
        } else {
            val throwableDeclaration = tryFindBuiltInType { it.throwable }
                ?: compilationException("kotlin.Throwable is not found in fragments", null)

            val tagFuncType = WasmRefNullType(WasmHeapType.Type(WasmSymbol(throwableDeclaration)))

            val throwableTagFuncType = WasmFunctionType(
                parameterTypes = listOf(tagFuncType),
                resultTypes = emptyList()
            )

            WasmTag(throwableTagFuncType)
        }

        return listOf(tag)
    }

    private fun getTypes(
        additionalRecGroupTypes: List<WasmTypeDeclaration>,
        canonicalFunctionTypes: Map<WasmFunctionType, WasmFunctionType>,
        additionalTypes: List<WasmTypeDeclaration>,
    ): List<RecursiveTypeGroup> {
        val gcTypes = wasmCompiledFileFragments.flatMap { it.gcTypes.elements }
        val vTableGcTypes = wasmCompiledFileFragments.flatMap { it.vTableGcTypes.elements }

        val recGroupTypes = sequence {
            yieldAll(additionalRecGroupTypes)
            yieldAll(gcTypes)
            yieldAll(vTableGcTypes)
            yieldAll(canonicalFunctionTypes.values)
        }

        val recursiveGroups = createRecursiveTypeGroups(recGroupTypes)

        recursiveGroups.forEach { group ->
            if (group.singleOrNull() is WasmArrayDeclaration) {
                return@forEach
            }

            val needMixIn = group.any { it in gcTypes }
            val needStableSort = needMixIn || group.any { it in vTableGcTypes }

            canonicalSort(group, needStableSort)

            if (needMixIn) {
                val firstIdSignature = group.firstOrNull { it is WasmStructDeclaration }?.let { firstStruct ->
                    wasmCompiledFileFragments.firstNotNullOfOrNull {
                        it.gcTypes.wasmToIr[firstStruct] ?: it.vTableGcTypes.wasmToIr[firstStruct]
                    }
                }
                if (firstIdSignature != null) {
                    val mixIn = WasmStructDeclaration("mixin_type", encodeIndex(firstIdSignature.toString().hashCode().toUInt()), null, true)
                    group.add(mixIn)
                }
            }
        }

        additionalTypes.forEach { recursiveGroups.add(mutableListOf(it)) }
        return recursiveGroups
    }

    private fun getGlobals(additionalTypes: MutableList<WasmTypeDeclaration>) = mutableListOf<WasmGlobal>().apply {
        wasmCompiledFileFragments.forEach { fragment ->
            addAll(fragment.globalFields.elements)
            addAll(fragment.globalVTables.elements)
            addAll(fragment.globalClassITables.elements.distinct())
        }
        createRttiTypeAndProcessRttiGlobals(this, additionalTypes)
    }

    private fun createAndExportMemory(exports: MutableList<WasmExport<*>>, moduleImportName: String?): List<WasmMemory> {
        val memorySizeInPages = 0
        val importPair = moduleImportName?.let { WasmImportDescriptor(it, WasmSymbol("memory")) }
        val memory = WasmMemory(WasmLimits(memorySizeInPages.toUInt(), null/* "unlimited" */), importPair)

        // Need to export the memory in order to pass complex objects to the host language.
        // Export name "memory" is a WASI ABI convention.
        val exportMemory = WasmExport.Memory("memory", memory)
        exports.add(exportMemory)
        return listOf(memory)
    }

    private fun createAndExportMasterInitFunction(
        fieldInitializerFunction: WasmFunction,
        tryGetAssociatedObject: WasmFunction,
        initializeUnit: Boolean,
    ): WasmFunction.Defined {
        val masterInitFunction = WasmFunction.Defined("_initialize", WasmSymbol(parameterlessNoReturnFunctionType))
        with(WasmExpressionBuilder(masterInitFunction.instructions)) {
            if (initializeUnit) {
                val unitGetInstance = tryFindBuiltInFunction { it.unitGetInstance }
                    ?: compilationException("kotlin.Unit_getInstance is not file in fragments", null)
                buildCall(WasmSymbol(unitGetInstance), serviceCodeLocation)
            }

            val registerModuleDescriptor = tryFindBuiltInFunction { it.registerModuleDescriptor }
                ?: compilationException("kotlin.registerModuleDescriptor is not file in fragments", null)
            buildInstr(WasmOp.REF_FUNC, serviceCodeLocation, WasmImmediate.FuncIdx(WasmSymbol(tryGetAssociatedObject)))
            buildInstr(WasmOp.CALL, serviceCodeLocation, WasmImmediate.FuncIdx(WasmSymbol(registerModuleDescriptor)))

            buildCall(WasmSymbol(fieldInitializerFunction), serviceCodeLocation)
            wasmCompiledFileFragments.forEach { fragment ->
                fragment.mainFunctionWrappers.forEach { signature ->
                    val wrapperFunction = fragment.functions.defined[signature]
                        ?: compilationException("Cannot find symbol for main wrapper", type = null)
                    buildCall(WasmSymbol(wrapperFunction), serviceCodeLocation)
                }
            }
            buildInstr(WasmOp.RETURN, serviceCodeLocation)
        }
        return masterInitFunction
    }

    private fun createAssociatedObjectGetterFunction(
        wasmElements: MutableList<WasmElement>,
        additionalTypes: MutableList<WasmTypeDeclaration>
    ): WasmFunction.Defined {
        val kotlinAny = tryFindBuiltInType { it.kotlinAny }
            ?: compilationException("kotlin.Any is not found in fragments", null)

        val nullableAnyWasmType = WasmRefNullType(WasmHeapType.Type(WasmSymbol(kotlinAny)))
        val associatedObjectGetterType = WasmFunctionType(listOf(WasmI64, WasmI64), listOf(nullableAnyWasmType))
        additionalTypes.add(associatedObjectGetterType)

        val associatedObjectGetter = WasmFunction.Defined("_associatedObjectGetter", WasmSymbol(associatedObjectGetterType))
        // Make this function possible to func.ref
        wasmElements.add(
            WasmElement(
                type = WasmFuncRef,
                values = listOf(WasmTable.Value.Function(WasmSymbol(associatedObjectGetter))),
                mode = WasmElement.Mode.Declarative
            )
        )

        val jsToKotlinAnyAdapter by lazy {
            tryFindBuiltInFunction { it.jsToKotlinAnyAdapter }
                ?: compilationException("kotlin.jsToKotlinAnyAdapter is not found in fragments", null)
        }

        val allDefinedFunctions = mutableMapOf<IdSignature, WasmFunction>()
        wasmCompiledFileFragments.forEach { allDefinedFunctions.putAll(it.functions.defined) }

        associatedObjectGetter.instructions.clear()
        with(WasmExpressionBuilder(associatedObjectGetter.instructions)) {
            wasmCompiledFileFragments.forEach { fragment ->
                for ((klassId, associatedObjectsInstanceGetters) in fragment.classAssociatedObjectsInstanceGetters) {
                    buildGetLocal(WasmLocal(0, "classId", WasmI64, true), serviceCodeLocation)
                    buildConstI64(klassId, serviceCodeLocation)
                    buildInstr(WasmOp.I64_EQ, serviceCodeLocation)
                    buildIf("Class matches")
                    associatedObjectsInstanceGetters.forEach { (keyId, getter, isExternal) ->
                        val getterFunction = allDefinedFunctions[getter]
                        if (getterFunction != null) { //Could be deleted with DCE
                            buildGetLocal(WasmLocal(1, "keyId", WasmI64, true), serviceCodeLocation)
                            buildConstI64(keyId, serviceCodeLocation)
                            buildInstr(WasmOp.I64_EQ, serviceCodeLocation)
                            buildIf("Object matches")
                            buildCall(WasmSymbol(getterFunction), serviceCodeLocation)
                            if (isExternal) {
                                buildCall(WasmSymbol(jsToKotlinAnyAdapter), serviceCodeLocation)
                            }
                            buildInstr(WasmOp.RETURN, serviceCodeLocation)
                            buildEnd()
                        }
                    }
                    buildEnd()
                }
            }
            buildRefNull(WasmHeapType.Simple.None, serviceCodeLocation)
            buildInstr(WasmOp.RETURN, serviceCodeLocation)
        }
        return associatedObjectGetter
    }

    private fun createStartUnitTestsFunction(): WasmFunction.Defined? {
        val runRootSuites = tryFindBuiltInFunction { it.runRootSuites } ?: return null
        val startUnitTestsFunction = WasmFunction.Defined("startUnitTests", WasmSymbol(parameterlessNoReturnFunctionType))
        with(WasmExpressionBuilder(startUnitTestsFunction.instructions)) {
            wasmCompiledFileFragments.forEach { fragment ->
                fragment.testFunctionDeclarators.forEach{ declarator ->
                    val declaratorFunction = fragment.functions.defined[declarator]
                        ?: compilationException("Cannot find symbol for test declarator", type = null)
                    buildCall(WasmSymbol(declaratorFunction), serviceCodeLocation)
                }
            }
            buildCall(WasmSymbol(runRootSuites), serviceCodeLocation)
        }
        return startUnitTestsFunction
    }

    private fun createFieldInitializerFunction(): WasmFunction.Defined {
        val fieldInitializerFunction = WasmFunction.Defined("_fieldInitialize", WasmSymbol(parameterlessNoReturnFunctionType))
        with(WasmExpressionBuilder(fieldInitializerFunction.instructions)) {
            wasmCompiledFileFragments.forEach { fragment ->
                fragment.objectInstanceFieldInitializers.forEach { objectInitializer ->
                    val function = fragment.functions.defined[objectInitializer]
                    if (function != null) {
                        val functionSymbol = WasmSymbol(function)
                        expression.add(
                            index = 0,
                            element = WasmInstrWithoutLocation(WasmOp.CALL, listOf(WasmImmediate.FuncIdx(functionSymbol)))
                        )
                    }
                }
                fragment.nonConstantFieldInitializers.forEach { nonConstantInitializer ->
                    val functionSymbol = WasmSymbol(fragment.functions.defined[nonConstantInitializer]!!)
                    buildCall(
                        symbol = functionSymbol,
                        location = serviceCodeLocation
                    )
                }
            }
        }
        return fieldInitializerFunction
    }

    private fun createStringPoolField(stringPoolSize: Int, kotlinStringType: WasmType, additionalTypes: MutableList<WasmTypeDeclaration>): Pair<WasmGlobal, WasmArrayDeclaration> {
        val wasmStringArrayDeclaration =
            WasmArrayDeclaration("string_array", WasmStructFieldDeclaration("string", kotlinStringType, true))
        additionalTypes.add(wasmStringArrayDeclaration)

        val stringCacheFieldInitializer = listOf(
            WasmInstrWithLocation(
                operator = WasmOp.I32_CONST,
                location = serviceCodeLocation,
                immediates = listOf(WasmImmediate.ConstI32(stringPoolSize))
            ),
            WasmInstrWithLocation(
                operator = WasmOp.ARRAY_NEW_DEFAULT,
                location = serviceCodeLocation,
                immediates = listOf(WasmImmediate.GcType(wasmStringArrayDeclaration))
            ),
        )

        val refToArrayOfNullableStringsType =
            WasmRefType(WasmHeapType.Type(WasmSymbol(wasmStringArrayDeclaration)))

        val global = WasmGlobal("_stringPool", refToArrayOfNullableStringsType, false, stringCacheFieldInitializer)
        return global to wasmStringArrayDeclaration
    }

    private fun createStringLiteralFunction(
        createStringFunction: WasmFunction,
        stringPoolGlobalField: WasmGlobal,
        additionalTypes: MutableList<WasmTypeDeclaration>,
        wasmElements: MutableList<WasmElement>,
        stringArrayType: WasmArrayDeclaration
    ): WasmFunction.Defined {
        val kotlinStringType = createStringFunction.type.owner.resultTypes[0]
        val wasmCharArrayType = createStringFunction.type.owner.parameterTypes[0]
        val wasmCharStructDeclaration = (wasmCharArrayType.getHeapType() as WasmHeapType.Type).type.owner

        val stringLiteralFunctionType = WasmFunctionType(listOf(WasmI32, WasmI32, WasmI32), listOf(kotlinStringType))
        additionalTypes.add(stringLiteralFunctionType)

        val poolIdLocal = WasmLocal(0, "poolId", WasmI32, true)
        val startAddress = WasmLocal(1, "startAddress", WasmI32, true)
        val length = WasmLocal(2, "length", WasmI32, true)
        val temporary = WasmLocal(3, "temporary", kotlinStringType, false)

        val stringLiteralFunction = WasmFunction.Defined("_stringLiteral", WasmSymbol(stringLiteralFunctionType), locals = mutableListOf(temporary))
        with(WasmExpressionBuilder(stringLiteralFunction.instructions)) {

            buildBlock("cache_check", kotlinStringType) { blockResult ->
                buildGetGlobal(WasmSymbol(stringPoolGlobalField), serviceCodeLocation)
                buildGetLocal(poolIdLocal, serviceCodeLocation)
                buildInstr(
                    WasmOp.ARRAY_GET,
                    serviceCodeLocation,
                    WasmImmediate.TypeIdx(stringArrayType)
                )
                buildBrInstr(WasmOp.BR_ON_NON_NULL, blockResult, serviceCodeLocation)

                // cache miss
                buildGetGlobal(WasmSymbol(stringPoolGlobalField), serviceCodeLocation)
                buildGetLocal(poolIdLocal, serviceCodeLocation)

                // create new string
                buildGetLocal(startAddress, serviceCodeLocation)
                buildGetLocal(length, serviceCodeLocation)
                buildInstr(
                    op = WasmOp.ARRAY_NEW_DATA,
                    location = serviceCodeLocation,
                    WasmImmediate.GcType(wasmCharStructDeclaration), WasmImmediate.DataIdx(0)
                )
                buildCall(WasmSymbol(createStringFunction), serviceCodeLocation)

                //remember and return string
                buildInstr(WasmOp.LOCAL_TEE, serviceCodeLocation, WasmImmediate.LocalIdx(temporary))
                buildInstr(
                    WasmOp.ARRAY_SET,
                    serviceCodeLocation,
                    WasmImmediate.TypeIdx(stringArrayType)
                )
                buildGetLocal(temporary, serviceCodeLocation)
            }
            buildInstr(WasmOp.RETURN, serviceCodeLocation)
        }

        // Make this function possible to func.ref
        wasmElements.add(
            WasmElement(
                type = WasmFuncRef,
                values = listOf(WasmTable.Value.Function(WasmSymbol(stringLiteralFunction))),
                mode = WasmElement.Mode.Declarative
            )
        )

        wasmCompiledFileFragments.forEach { fragment ->
            fragment.createStringLiteral?.bind(stringLiteralFunction)
            fragment.createStringLiteralType?.bind(stringLiteralFunctionType)
        }

        return stringLiteralFunction
    }

    private fun bindUnboundSymbols() {
        bindFileFragments(wasmCompiledFileFragments, { it.functions.unbound }, { it.functions.defined })
        bindFileFragments(wasmCompiledFileFragments, { it.globalFields.unbound }, { it.globalFields.defined })
        bindFileFragments(wasmCompiledFileFragments, { it.globalVTables.unbound }, { it.globalVTables.defined })
        bindFileFragments(wasmCompiledFileFragments, { it.gcTypes.unbound }, { it.gcTypes.defined })
        bindFileFragments(wasmCompiledFileFragments, { it.vTableGcTypes.unbound }, { it.vTableGcTypes.defined })
        bindFileFragments(wasmCompiledFileFragments, { it.globalClassITables.unbound }, { it.globalClassITables.defined })
        bindFileFragments(wasmCompiledFileFragments, { it.functionTypes.unbound }, { it.functionTypes.defined })
        rebindEquivalentFunctions()
        bindUniqueJsFunNames()
    }

    private fun <IrSymbolType, WasmDeclarationType : Any, WasmSymbolType : WasmSymbol<WasmDeclarationType>> bindFileFragments(
        fragments: List<WasmCompiledFileFragment>,
        unboundSelector: (WasmCompiledFileFragment) -> Map<IrSymbolType, WasmSymbolType>,
        definedSelector: (WasmCompiledFileFragment) -> Map<IrSymbolType, WasmDeclarationType>,
    ) {
        val allDefined = mutableMapOf<IrSymbolType, WasmDeclarationType>()
        fragments.forEach { fragment ->
            definedSelector(fragment).forEach { defined ->
                check(!allDefined.containsKey(defined.key)) {
                    "Redeclaration of symbol ${defined.key}"
                }
                allDefined[defined.key] = defined.value
            }
        }
        for (fragment in fragments) {
            val unbound = unboundSelector(fragment)
            bind(unbound, allDefined)
        }
    }

    private fun bindUnboundFunctionTypes(): Map<WasmFunctionType, WasmFunctionType> {
        // Associate function types to a single canonical function type
        val canonicalFunctionTypes = LinkedHashMap<WasmFunctionType, WasmFunctionType>()
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.functionTypes.elements.associateWithTo(canonicalFunctionTypes) { it }
        }
        // Rebind symbol to canonical
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.functionTypes.unbound.forEach { (_, wasmSymbol) ->
                wasmSymbol.bind(canonicalFunctionTypes.getValue(wasmSymbol.owner))
            }
        }
        return canonicalFunctionTypes
    }

    private fun bindStringPoolSymbolsAndGetSize(data: MutableList<WasmData>): Int {
        val stringDataSectionBytes = mutableListOf<Byte>()
        var stringDataSectionStart = 0
        val stringAddressAndId = mutableMapOf<String, Pair<Int, Int>>()
        wasmCompiledFileFragments.forEach { fragment ->
            for ((string, literalAddressSymbol) in fragment.stringLiteralAddress.unbound) {
                val currentStringAddress: Int
                val currentStringId: Int
                val addressAndId = stringAddressAndId[string]
                if (addressAndId == null) {
                    currentStringAddress = stringDataSectionStart
                    currentStringId = stringAddressAndId.size
                    stringAddressAndId[string] = currentStringAddress to currentStringId

                    val constData = ConstantDataCharArray(string.toCharArray())
                    stringDataSectionBytes += constData.toBytes().toList()
                    stringDataSectionStart += constData.sizeInBytes
                } else {
                    currentStringAddress = addressAndId.first
                    currentStringId = addressAndId.second
                }

                val literalPoolIdSymbol = fragment.stringLiteralPoolId.unbound[string]
                    ?: compilationException("String symbol expected", type = null)
                literalAddressSymbol.bind(currentStringAddress)
                literalPoolIdSymbol.bind(currentStringId)
            }
        }

        data.add(WasmData(WasmDataMode.Passive, stringDataSectionBytes.toByteArray()))
        return stringAddressAndId.size
    }

    private fun bindConstantArrayDataSegmentIds(data: MutableList<WasmData>) {
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.constantArrayDataSegmentId.unbound.forEach { (constantArraySegment, symbol) ->
                symbol.bind(data.size)
                val integerSize = when (constantArraySegment.second) {
                    WasmI8 -> BYTE_SIZE_BYTES
                    WasmI16 -> SHORT_SIZE_BYTES
                    WasmI32 -> INT_SIZE_BYTES
                    WasmI64 -> LONG_SIZE_BYTES
                    else -> TODO("type ${constantArraySegment.second} is not implemented")
                }
                val constData = ConstantDataIntegerArray(constantArraySegment.first, integerSize)
                data.add(WasmData(WasmDataMode.Passive, constData.toBytes()))
            }
        }
    }

    private fun bindUniqueJsFunNames() {
        val jsCodeCounter = mutableMapOf<String, Int>()
        wasmCompiledFileFragments.forEach { fragment ->
            fragment.jsFuns.forEach { jsCodeSnippet ->
                val jsFunName = jsCodeSnippet.value.importName.owner
                val counterValue = jsCodeCounter.getOrPut(jsFunName, defaultValue = { 0 })
                jsCodeCounter[jsFunName] = counterValue + 1
                if (counterValue > 0) {
                    jsCodeSnippet.value.importName.bind("${jsFunName}_$counterValue")
                }
            }
        }
    }

    private fun rebindEquivalentFunctions() {
        val equivalentFunctions = mutableMapOf<String, WasmFunction>()
        wasmCompiledFileFragments.forEach { fragment ->
            for ((signatureString, idSignature) in fragment.equivalentFunctions) {
                val func = equivalentFunctions[signatureString]
                if (func == null) {
                    // First occurrence of the adapter, register it (if not removed by DCE).
                    val functionToUse = fragment.functions.defined[idSignature] ?: continue
                    equivalentFunctions[signatureString] = functionToUse
                } else {
                    // Adapter already exists, remove this one and use the existing adapter.
                    fragment.functions.defined.remove(idSignature)?.let { duplicate ->
                        fragment.functions.elements.remove(duplicate)
                        fragment.functions.wasmToIr.remove(duplicate)
                        fragment.exports.removeAll { it.field == duplicate }
                    }
                    fragment.jsFuns.remove(idSignature)
                    fragment.jsModuleImports.remove(idSignature)

                    // Rebind adapter function to the single instance
                    // There might not be any unbound references in case it's called only from JS side
                    fragment.functions.unbound[idSignature]?.bind(func)
                }
            }
        }
    }
}

fun <IrSymbolType, WasmDeclarationType : Any, WasmSymbolType : WasmSymbol<WasmDeclarationType>> bind(
    unbound: Map<IrSymbolType, WasmSymbolType>,
    defined: Map<IrSymbolType, WasmDeclarationType>
) {
    unbound.forEach { (irSymbol, wasmSymbol) ->
        if (irSymbol !in defined)
            compilationException("Can't link symbol ${irSymbolDebugDump(irSymbol)}", type = null)
        if (!wasmSymbol.isBound()) {
            wasmSymbol.bind(defined.getValue(irSymbol))
        }
    }
}

private fun irSymbolDebugDump(symbol: Any?): String =
    when (symbol) {
        is IrFunctionSymbol -> "function ${symbol.owner.fqNameWhenAvailable}"
        is IrClassSymbol -> "class ${symbol.owner.fqNameWhenAvailable}"
        else -> symbol.toString()
    }

fun alignUp(x: Int, alignment: Int): Int {
    assert(alignment and (alignment - 1) == 0) { "power of 2 expected" }
    return (x + alignment - 1) and (alignment - 1).inv()
}

data class ClassAssociatedObjects(
    val klass: Long,
    val objects: List<AssociatedObject>
)

data class AssociatedObject(
    val obj: Long,
    val getterFunc: IdSignature,
    val isExternal: Boolean,
)