// DONT_TARGET_EXACT_BACKEND: JVM_IR
// ^ @AssociatedObjectKey is not available in Kotlin/JVM
// WITH_STDLIB

import kotlin.reflect.*

@OptIn(ExperimentalAssociatedObjects::class)
@AssociatedObjectKey
@Retention(AnnotationRetention.BINARY)
annotation class Associated1(val kClass: KClass<*>)

@Associated1(Bar::class)
class Foo

object Bar

@OptIn(ExperimentalAssociatedObjects::class)
fun box(): String {
    if (Foo::class.findAssociatedObject<Associated1>() != Bar) return "fail 1"

    return "OK"
}