// JVM_FILE_NAME: ContractsKt
// KNM_FE10_IGNORE
// ^test infrastrucutre problem – the compiled file has dummy.kt name

package test

import kotlin.contracts.*

@OptIn(ExperimentalContracts::class)
fun myRequire(x: Boolean) {
    contract {
        returns(true) implies (x)
    }
}