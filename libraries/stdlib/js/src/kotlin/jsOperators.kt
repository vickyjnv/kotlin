/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.js

@kotlin.internal.InlineOnly
@Suppress("UNUSED_PARAMETER")
internal inline fun jsDeleteProperty(obj: Any, property: Any) {
    js("delete obj[property]")
}

@kotlin.internal.InlineOnly
@Suppress("UNUSED_PARAMETER")
internal inline fun jsBitwiseOr(lhs: Any, rhs: Any): Int =
    js("lhs | rhs").unsafeCast<Int>()

/**
 * Function corresponding to JavaScript's `typeof` operator
 */
@kotlin.internal.InlineOnly
@Suppress("UNUSED_PARAMETER")
public inline fun jsTypeOf(a: Any?): String =
    js("typeof a").unsafeCast<String>()