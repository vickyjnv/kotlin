/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.impl

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.impl.ModuleAwareClassDescriptor.Companion.getRefinedMemberScopeIfPossible
import org.jetbrains.kotlin.descriptors.impl.ModuleAwareClassDescriptor.Companion.getRefinedUnsubstitutedMemberScopeIfPossible
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.TypeSubstitution

abstract class ModuleAwareClassDescriptor : ClassDescriptor {
    protected abstract fun getUnsubstitutedMemberScope(moduleDescriptor: ModuleDescriptor): MemberScope
    protected abstract fun getMemberScope(typeSubstitution: TypeSubstitution, moduleDescriptor: ModuleDescriptor): MemberScope
    protected abstract fun getMemberScope(typeArguments: List<TypeProjection>, moduleDescriptor: ModuleDescriptor): MemberScope

    companion object {
        internal fun ClassDescriptor.getRefinedUnsubstitutedMemberScopeIfPossible(
            moduleDescriptor: ModuleDescriptor
        ): MemberScope =
            (this as? ModuleAwareClassDescriptor)?.getUnsubstitutedMemberScope(moduleDescriptor) ?: this.unsubstitutedMemberScope

        internal fun ClassDescriptor.getRefinedMemberScopeIfPossible(
            typeSubstitution: TypeSubstitution,
            moduleDescriptor: ModuleDescriptor
        ): MemberScope =
            (this as? ModuleAwareClassDescriptor)?.getMemberScope(typeSubstitution, moduleDescriptor) ?: this.getMemberScope(
                typeSubstitution
            )
    }
}

fun ClassDescriptor.getRefinedUnsubstitutedMemberScopeIfPossible(
    moduleDescriptor: ModuleDescriptor
): MemberScope = getRefinedUnsubstitutedMemberScopeIfPossible(moduleDescriptor)

fun ClassDescriptor.getRefinedMemberScopeIfPossible(
    typeSubstitution: TypeSubstitution,
    moduleDescriptor: ModuleDescriptor
): MemberScope = getRefinedMemberScopeIfPossible(typeSubstitution, moduleDescriptor)
