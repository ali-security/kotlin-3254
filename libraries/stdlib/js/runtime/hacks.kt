/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

import kotlin.internal.UsedFromCompiler

@PublishedApi
internal fun throwUninitializedPropertyAccessException(name: String): Nothing =
    throw UninitializedPropertyAccessException("lateinit property $name has not been initialized")

@PublishedApi
internal fun throwUnsupportedOperationException(message: String): Nothing =
    throw UnsupportedOperationException(message)

@PublishedApi
internal fun throwKotlinNothingValueException(): Nothing =
    throw KotlinNothingValueException()

@UsedFromCompiler
internal fun noWhenBranchMatchedException(): Nothing = throw NoWhenBranchMatchedException()

@UsedFromCompiler
internal fun THROW_ISE(): Nothing {
    throw IllegalStateException()
}

@UsedFromCompiler
internal fun THROW_CCE(): Nothing {
    throw ClassCastException()
}

@UsedFromCompiler
internal fun THROW_NPE(): Nothing {
    throw NullPointerException()
}

@UsedFromCompiler
internal fun THROW_IAE(msg: String): Nothing {
    throw IllegalArgumentException(msg)
}

@UsedFromCompiler
internal fun <T:Any> ensureNotNull(v: T?): T = if (v == null) THROW_NPE() else v
