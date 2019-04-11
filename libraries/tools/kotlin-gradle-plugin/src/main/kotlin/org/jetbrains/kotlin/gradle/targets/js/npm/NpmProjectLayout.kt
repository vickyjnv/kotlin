/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.npm

import org.gradle.api.Project
import org.gradle.process.ExecSpec
import org.gradle.process.ProcessForkOptions
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import java.io.File

class NpmProjectLayout(val project: Project, val nodeWorkDir: File) {
    val nodeModulesDir
        get() = nodeWorkDir.resolve(NODE_MODULES)

    val packageJsonFile: File
        get() {
            return nodeWorkDir.resolve(PACKAGE_JSON)
        }

    fun useTool(exec: ExecSpec, tool: String, vararg args: String) {
        NpmResolver.resolve(project)

        exec.workingDir = nodeWorkDir
        exec.executable = NodeJsPlugin[project].buildEnv().nodeExecutable
        exec.args = listOf(findModule(tool)) + args
    }

    fun findModule(name: String) =
        nodeModulesDir.resolve(name).also { file ->
            check(file.isFile) { "Cannot find ${file.canonicalPath}" }
        }.canonicalPath

    companion object {
        const val PACKAGE_JSON = "package.json"
        const val NODE_MODULES = "node_modules"

        operator fun get(project: Project): NpmProjectLayout {
            val manageNodeModules = NodeJsPlugin[project].manageNodeModules

            val nodeWorkDir =
                if (manageNodeModules) project.rootDir
                else project.buildDir

            return NpmProjectLayout(project, nodeWorkDir)
        }
    }
}