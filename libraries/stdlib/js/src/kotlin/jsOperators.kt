/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */


@file:Suppress("UNUSED_PARAMETER")

package kotlin.js

/**
 * Function corresponding to JavaScript's `typeof` operator
 */
@kotlin.internal.InlineOnly
public inline fun jsTypeOf(a: Any?): String =
    js("typeof a").unsafeCast<String>()

@kotlin.internal.InlineOnly
internal inline fun jsDeleteProperty(obj: Any, property: Any) {
    js("delete obj[property]")
}

@kotlin.internal.InlineOnly
internal inline fun jsBitwiseAnd(lhs_hack: Any?, rhs_hack: Any?): Int =
    js("lhs_hack & rhs_hack").unsafeCast<Int>()

@kotlin.internal.InlineOnly
internal inline fun jsBitwiseOr(lhs: Any?, rhs: Any?): Int =
    js("lhs | rhs").unsafeCast<Int>()

@kotlin.internal.InlineOnly
internal inline fun jsInstanceOf(obj: Any?, jsClass: Any?): Boolean =
    js("obj instanceof jsClass").unsafeCast<Boolean>()

// Returns true if the specified property is in the specified object or its prototype chain.
@kotlin.internal.InlineOnly
internal inline fun jsIn(lhs: Any?, rhs: Any): Boolean =
    js("lhs in rhs").unsafeCast<Boolean>()
