// JDK_KIND: FULL_JDK_21
// DIAGNOSTICS: -WRONG_NULLABILITY_FOR_JAVA_OVERRIDE
// LANGUAGE: -ForbidBridgesConflictingWithInheritedMethodsInJvmCode
// WITH_STDLIB

// FILE: 1.kt
import java.util.*

class A : LinkedList<Int>(), SequencedCollection<Int>

<!ACCIDENTAL_OVERRIDE_BY_BRIDGE_METHOD_WARNING!>class B : LinkedList<Int>(), SequencedCollection<Int> {
    override fun addFirst(e: Int?) { }
    override fun reversed(): LinkedList<Int> {
        return null!!
    }
}<!>

fun test(a: A, b: B){
    a.size
    a.remove(element = 1)
    a.addFirst(3)
    a.removeFirst()
    a.removeLast()
    a.reversed()

    b.reversed()
    b.addFirst(1)
}
