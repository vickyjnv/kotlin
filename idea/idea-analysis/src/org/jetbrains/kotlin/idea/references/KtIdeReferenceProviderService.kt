/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.references

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import org.jetbrains.kotlin.psi.KotlinReferenceProvidersService

class KtIdeReferenceProviderService : KotlinReferenceProvidersService() {
    override fun getReferences(psiElement: PsiElement): Array<PsiReference> {
        return ReferenceProvidersRegistry.getReferencesFromProviders(psiElement)
    }
}