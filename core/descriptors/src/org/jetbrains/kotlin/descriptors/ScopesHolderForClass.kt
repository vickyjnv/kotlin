/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors

import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.storage.MemoizedFunctionToNotNull
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.storage.getValue
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.isExpectClass
import org.jetbrains.kotlin.utils.DFS

class ScopesHolderForClass<T : MemberScope> private constructor(
    private val classDescriptor: ClassDescriptor,
    private val storageManager: StorageManager,
    private val scopeFactory: (ModuleDescriptor) -> T
) {
    private val scopeForOwnerModule by storageManager.createLazyValue { scopeFactory(classDescriptor.module) }

    private val scopeOrMemoizedFunction by storageManager.createLazyValue<Any> {
        val typeConstructor = classDescriptor.typeConstructor

        if (typeConstructor.areThereExpectSupertypes())
            storageManager.createMemoizedFunction(scopeFactory)
        else
            scopeForOwnerModule
    }

    fun getScope(moduleDescriptor: ModuleDescriptor): T {
        if (classDescriptor.module === moduleDescriptor) return scopeForOwnerModule

        @Suppress("UNCHECKED_CAST")
        return when (scopeOrMemoizedFunction) {
            is MemoizedFunctionToNotNull<*, *> ->
                (scopeOrMemoizedFunction as MemoizedFunctionToNotNull<ModuleDescriptor, T>).invoke(moduleDescriptor)
            else -> scopeOrMemoizedFunction as T
        }
    }

    companion object {
        fun <T : MemberScope> create(
            classDescriptor: ClassDescriptor,
            storageManager: StorageManager,
            scopeFactory: (ModuleDescriptor) -> T
        ): ScopesHolderForClass<T> {
            return ScopesHolderForClass(classDescriptor, storageManager, scopeFactory)
        }
    }
}

fun TypeConstructor.areThereExpectSupertypes(): Boolean {
    var result = false
    DFS.dfs(
        listOf(this),
        DFS.Neighbors { current ->
            current.supertypes.map { it.constructor }
        },
        DFS.VisitedWithSet(),
        object : DFS.AbstractNodeHandler<TypeConstructor, Unit>() {
            override fun beforeChildren(current: TypeConstructor): Boolean {
                if (current.isExpectClass()) {
                    result = true
                    return false
                }
                return true
            }

            override fun result() = Unit
        }
    )

    return result
}
