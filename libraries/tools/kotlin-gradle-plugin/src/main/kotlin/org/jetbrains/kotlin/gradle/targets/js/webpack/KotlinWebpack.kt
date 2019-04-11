/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.webpack

import org.gradle.api.DefaultTask
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.internal.DefaultProcessForkOptions
import org.gradle.process.internal.ExecHandleFactory
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilationToRunnableFiles
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmProjectLayout
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmResolver
import org.jetbrains.kotlin.gradle.utils.injected
import java.io.File
import javax.inject.Inject

open class KotlinWebpack : DefaultTask() {
    @get:Inject
    open val fileResolver: FileResolver
        get() = injected

    @get:Inject
    open val execHandleFactory: ExecHandleFactory
        get() = injected

    @Input
    @SkipWhenEmpty
    open lateinit var entry: File

    @TaskAction
    fun execute() {
        NpmResolver.resolve(project)

        val npmProjectLayout = NpmProjectLayout[project]

        val configFile = project.buildDir.resolve("webpack.config.js")

        val outputPath = project.buildDir.resolve("lib").absolutePath

        //language=JavaScript 1.8
        configFile.writeText("""
module.exports = {
  mode: 'development',
  entry: '${entry.absolutePath}',
  output: {
    path: '$outputPath',
    filename: '${entry.name}'
  },
  resolve: {
    modules: [
      "node_modules"
    ]
  }
};
        """.trimIndent())

        val execFactory = execHandleFactory.newExec()
        npmProjectLayout.useTool(
            execFactory,
            ".bin/webpack",
            "--config", configFile.absolutePath
        )
        val exec = execFactory.build()
        exec.start()
        exec.waitForFinish()
    }

    companion object {
        fun configure(compilation: KotlinCompilationToRunnableFiles<*>) {
            compilation.dependencies {
                runtimeOnly(npm("webpack", "4.29.6"))
                runtimeOnly(npm("webpack-cli", "3.3.0"))
            }
        }
    }
}