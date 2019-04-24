/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.checker.NewKotlinTypeChecker
import org.jetbrains.kotlin.types.checker.RefineKotlinTypeChecker
import org.jetbrains.kotlin.types.typeUtil.refinedSupertypesIfNeeded

fun KotlinType.refineTypeIfNeeded(
    moduleDescriptor: ModuleDescriptor,
    languageVersionSettings: LanguageVersionSettings
): KotlinType {
    if (!languageVersionSettings.getFlag(AnalysisFlags.useTypeRefinement)) return this

    return refine(moduleDescriptor)
}

fun ClassDescriptor.refinedSupertypesIfNeeded(
    moduleDescriptor: ModuleDescriptor,
    languageVersionSettings: LanguageVersionSettings
): Collection<KotlinType> = refinedSupertypesIfNeeded(moduleDescriptor, languageVersionSettings.getFlag(AnalysisFlags.useTypeRefinement))

class RefineKotlinTypeCheckerImpl(
    private val moduleDescriptor: ModuleDescriptor,
    private val languageVersionSettings: LanguageVersionSettings
) : RefineKotlinTypeChecker {
    override fun isSubtypeOf(subtype: KotlinType, supertype: KotlinType): Boolean {
        if (!languageVersionSettings.getFlag(AnalysisFlags.useTypeRefinement)) return NewKotlinTypeChecker.isSubtypeOf(subtype, supertype)

        return NewKotlinTypeChecker.isSubtypeOf(subtype.refine(moduleDescriptor), supertype)
    }
}
