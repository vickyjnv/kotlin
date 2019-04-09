/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.yarn

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExtension.Companion.NODE_JS
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsSetupTask

open class YarnPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        this.extensions.create(YarnExtension.YARN, YarnExtension::class.java, this)
        tasks.create(YarnSetupTask.NAME, YarnSetupTask::class.java) {
            it.dependsOn(NodeJsPlugin.ensureAppliedInHierarchy(this).tasks.findByName(NodeJsSetupTask.NAME))
        }
    }

    companion object {
        fun ensureAppliedInHierarchy(myProject: Project): Project {
            return findInHeirarchy(myProject) ?: myProject.also {
                it.pluginManager.apply(YarnPlugin::class.java)
            }
        }

        fun findInHeirarchy(myProject: Project): Project? {
            var project: Project? = myProject
            while (project != null) {
                if (myProject.plugins.hasPlugin(YarnPlugin::class.java)) return project
                project = project.parent
            }

            return null
        }
    }
}
