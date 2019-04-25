/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.references

import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.psi.KotlinReferenceProvidersService
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.utils.SmartList

interface KotlinPsiReferenceProvider {
    fun getReferencesByElement(element: PsiElement): Array<PsiReference>
}

class KotlinPsiReferenceRegistrar {
    val providers: MultiMap<Class<out PsiElement>, KotlinPsiReferenceProvider> = MultiMap.create()

    inline fun <reified E : KtElement> registerProvider(
        crossinline factory: (E) -> PsiReference?
    ) {
        registerMultiProvider<E> { element -> factory(element)?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY }
    }

    inline fun <reified E : KtElement> registerMultiProvider(
        crossinline factory: (E) -> Array<PsiReference>
    ) {
        val provider: KotlinPsiReferenceProvider = object : KotlinPsiReferenceProvider {
            override fun getReferencesByElement(element: PsiElement): Array<PsiReference> {
                @Suppress("UNCHECKED_CAST")
                return factory(element as E)
            }
        }

        registerMultiProvider(E::class.java, provider)
    }

    fun registerMultiProvider(klass: Class<out PsiElement>, provider: KotlinPsiReferenceProvider) {
        providers.putValue(klass, provider)
    }
}

class KtIdeReferenceProviderService : KotlinReferenceProvidersService() {
    private val referenceProviders: MultiMap<Class<out PsiElement>, KotlinPsiReferenceProvider>

    init {
        val registrar = KotlinPsiReferenceRegistrar()
        KotlinReferenceContributor().registerReferenceProviders(registrar)
        referenceProviders = registrar.providers
    }

    private fun doGetKotlinReferencesFromProviders(context: PsiElement): Array<PsiReference> {
        val providers: Collection<KotlinPsiReferenceProvider> = referenceProviders.get(context.javaClass)
        if (providers.isEmpty()) return PsiReference.EMPTY_ARRAY

        val result = SmartList<PsiReference>()
        for (provider in providers) {
            try {
                val refs = provider.getReferencesByElement(context)
                result.addAll(refs)
            } catch (ignored: IndexNotReadyException) {
                // Ignore and continue to next provider
            }
        }

        if (result.isEmpty()) return PsiReference.EMPTY_ARRAY

        return result.toTypedArray()
    }

    override fun getReferences(psiElement: PsiElement): Array<PsiReference> {
        return CachedValuesManager.getCachedValue(psiElement) {
            CachedValueProvider.Result.create(
                doGetKotlinReferencesFromProviders(psiElement),
                PsiModificationTracker.MODIFICATION_COUNT
            )
        }
    }
}