/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.yarn

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.internal.logging.events.ProgressStartEvent
import org.gradle.process.internal.ExecActionFactory
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.nodeJsSetupWithoutTasks
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmApi
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

object Yarn : NpmApi {
    private val log = LoggerFactory.getLogger("org.jetbrains.kotlin.gradle.targets.js.yarn.Yarn")

    override fun setup(project: Project) {
        nodeJsSetupWithoutTasks(project)

        val yarnProject = YarnPlugin.ensureAppliedInHierarchy(project)
        val yarn = YarnExtension[yarnProject]
        val yarnEnv = yarn.buildEnv()
        if (yarn.download) {
            if (!yarnEnv.home.isDirectory) {
                (yarnProject.tasks.findByName(YarnSetupTask.NAME) as YarnSetupTask).setup()
            }
        }
    }

    fun execute(project: Project, workingDir: File = project.rootDir, vararg args: String) {
        val nodeJs = NodeJsExtension[project]
        val nodeJsEnv = nodeJs.buildEnv()

        val yarn = YarnExtension[project]
        val yarnEnv = yarn.buildEnv()

        val stderr = ByteArrayOutputStream()
        val stdout = StringBuilder()
        val stdInPipe = PipedInputStream()
        val services = (project as ProjectInternal).services
        val exec = services.get(ExecActionFactory::class.java).newExecAction()
        val progressFactory = services.get(org.gradle.internal.logging.progress.ProgressLoggerFactory::class.java)
        val op = progressFactory.newOperation(ProgressStartEvent.BUILD_OP_CATEGORY)
        op.start("Resolving NPM dependencies using yarn", "")
        exec.executable = nodeJsEnv.nodeExec
        exec.args = listOf(yarnEnv.home.resolve("bin/yarn.js").absolutePath) + args
        exec.workingDir = workingDir
        exec.errorOutput = stderr
        exec.standardOutput = PipedOutputStream(stdInPipe)
        val outputReaderThread = thread(name = "yarn output reader") {
            try {
                stdInPipe.reader().useLines { lines ->
                    lines.forEach {
                        stdout.appendln(it)
                        op.progress(it)
                    }
                }
            } catch (t: Throwable) {
                log.error("Error creating TCServiceMessagesClient", t)
            }
        }
        exec.isIgnoreExitValue = true
        if (exec.execute().exitValue != 0) {
            println("Yarn failed to resolve NPM dependencies:")
            stderr.writeTo(System.err)
            System.out.print(stdout.toString())
        }
        outputReaderThread.join()

        op.completed()
    }

    override fun resolveRootProject(project: Project) {
        execute(project)
    }
}
