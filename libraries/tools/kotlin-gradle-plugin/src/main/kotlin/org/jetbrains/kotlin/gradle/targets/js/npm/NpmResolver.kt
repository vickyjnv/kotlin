/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.npm

import com.google.gson.GsonBuilder
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin

/**
 * [NpmResolver] runs selected [NodeJsExtension.packageManager] on root project
 * with configured `package.json` files in all projects.
 *
 * `package.json`
 *
 * - compile(npm(...)) dependencies
 * - compile(npm(...)) dependencies from external modules
 * - compile(non npm(...)) dependencies transitively
 */
internal class NpmResolver private constructor(val rootProject: Project) {
    companion object {
        private const val PROJECT_RESOLUTION_IN_PROGRESS_EXTENSION = "resolvingYarnDependencies"
        private const val PROJECT_RESOLUTION_EXTENSION = "resolvedYarnDependencies"

        fun resolve(project: Project): ResolutionCallResult {
            if (project.rootProject.extensions.findByName(PROJECT_RESOLUTION_IN_PROGRESS_EXTENSION) != null)
                return ResolutionCallResult.AlreadyInProgress()

            val resolution = project.extensions.findByName(PROJECT_RESOLUTION_EXTENSION)
            return if (resolution != null) ResolutionCallResult.AlreadyResolved(resolution as ResolvedProject)
            else {
                NpmResolver(project.rootProject).resolveRoot()
                ResolutionCallResult.ResolvedNow(project.extensions.findByName(PROJECT_RESOLUTION_EXTENSION) as ResolvedProject)
            }
        }
    }

    sealed class ResolutionCallResult {
        class AlreadyInProgress : ResolutionCallResult()
        class AlreadyResolved(val resolution: ResolvedProject) : ResolutionCallResult()
        class ResolvedNow(val resolution: ResolvedProject) : ResolutionCallResult()
    }

    val allWorkspaces = mutableListOf<Project>()
    val gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    class ResolvedProject(val dependencies: Set<NpmDependency>)
    class Resolving(var inProgress: Boolean = true)

    private fun resolveRoot() {
        val gradleComponents = NodeModulesGradleComponents(rootProject)
        gradleComponents.loadOldState()

        resolve(rootProject, gradleComponents) {
            if (allWorkspaces.isNotEmpty()) {
                private = true
                workspaces = allWorkspaces.map { it.rootDir.relativeTo(rootProject.rootDir).path }
            }
        }

        val packageManager = NodeJsExtension[NodeJsPlugin.ensureAppliedInHierarchy(rootProject)].packageManager
        packageManager.setup(rootProject)
        packageManager.resolveRootProject(rootProject)

        gradleComponents.sync()
    }

    private fun resolve(project: Project, gradleComponents: NodeModulesGradleComponents, body: PackageJson.() -> Unit) {
        if (project.extensions.findByName(PROJECT_RESOLUTION_IN_PROGRESS_EXTENSION) != null) return

        val resolving = Resolving()
        project.extensions.add(PROJECT_RESOLUTION_IN_PROGRESS_EXTENSION, resolving)

        val resolved = project.extensions.findByName(PROJECT_RESOLUTION_EXTENSION)
        if (resolved != null) {
            error("yarn dependencies for $project already resolved")
        }

        project.subprojects.forEach {
            resolve(it)
        }

        savePackageJson(project, gradleComponents, body)

        allWorkspaces.add(project)

        resolving.inProgress = false
    }

    private fun savePackageJson(project: Project, gradleComponents: NodeModulesGradleComponents, body: PackageJson.() -> Unit) {
        val packageJson = PackageJson(project.name, project.version.toString())
        val transitiveDependencies = mutableListOf<NodeModulesGradleComponents.TransitiveNpmDependency>()

        val kotlin = project.kotlinExtension
        val npmDependencies = mutableSetOf<NpmDependency>()
        when (kotlin) {
            is KotlinSingleTargetExtension -> visitTarget(kotlin.target, project, npmDependencies, gradleComponents, transitiveDependencies)
            is KotlinMultiplatformExtension -> kotlin.targets.forEach {
                visitTarget(it, project, npmDependencies, gradleComponents, transitiveDependencies)
            }
        }

        project.extensions.add(PROJECT_RESOLUTION_EXTENSION, ResolvedProject(npmDependencies))

        npmDependencies.forEach {
            packageJson.dependencies[it.key] = chooseVersion(packageJson.dependencies[it.key], it.version)
        }

        transitiveDependencies.forEach {
            packageJson.dependencies[it.key] = chooseVersion(packageJson.dependencies[it.key], it.version)
        }

        project.extensions.findByType(NodeJsExtension::class.java)
            ?.packageJsonHandlers
            ?.forEach {
                it(packageJson)
            }
        packageJson.body()

        project.rootDir.resolve("package.json").writer().use {
            gson.toJson(packageJson, it)
        }
    }

    private fun chooseVersion(oldVersion: String?, newVersion: String): String =
        oldVersion ?: newVersion // todo: real versions conflict resolution

    private fun visitTarget(
        target: KotlinTarget,
        project: Project,
        npmDependencies: MutableSet<NpmDependency>,
        gradleComponents: NodeModulesGradleComponents,
        transitiveDependencies: MutableList<NodeModulesGradleComponents.TransitiveNpmDependency>
    ) {
        if (target.platformType == KotlinPlatformType.js) {
            target.compilations.toList().forEach { compilation ->
                compilation.relatedConfigurationNames.forEach {
                    project.configurations.getByName(it).allDependencies.forEach { dependency ->
                        when (dependency) {
                            is NpmDependency -> npmDependencies.add(dependency)
                        }
                    }
                }

                gradleComponents.visitCompilation(compilation, project, transitiveDependencies)
            }
        }
    }

}