// LANGUAGE: +NestedTypeAliases
// IGNORE_BACKEND_K1: WASM
// TARGET_BACKEND: WASM

// FILE: nestedTypeAliasSimpleWasmImport.mjs
export function add(x, y) { return x + y; }

// FILE: nestedTypeAliasSimpleWasmImport.kt
class Holder {
    typealias I = Int
}

@WasmImport("./nestedTypeAliasSimpleWasmImport.mjs")
external fun add(a: Holder.I, b: Holder.I): Holder.I

fun box(): String =
    if (add(2, 2) == 4) "OK" else "FAIL"

