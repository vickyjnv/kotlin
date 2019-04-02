/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.js

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.SmartList
import org.jetbrains.kotlin.cli.common.*
import org.jetbrains.kotlin.cli.common.ExitCode.COMPILATION_ERROR
import org.jetbrains.kotlin.cli.common.ExitCode.OK
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JsArgumentConstants
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.*
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageUtil
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.plugins.PluginCliParser
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.js.IncrementalDataProvider
import org.jetbrains.kotlin.incremental.js.IncrementalResultsConsumer
import org.jetbrains.kotlin.ir.backend.js.KlibModuleRef
import org.jetbrains.kotlin.ir.backend.js.compile
import org.jetbrains.kotlin.ir.backend.js.generateKLib
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.metadata.JsKlibMetadataSerializationUtil
import org.jetbrains.kotlin.js.config.EcmaVersion
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.js.config.JsConfig
import org.jetbrains.kotlin.js.config.SourceMapSourceEmbedding
import org.jetbrains.kotlin.js.facade.MainCallParameters
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.serialization.js.ModuleKind
import org.jetbrains.kotlin.utils.JsMetadataVersion
import org.jetbrains.kotlin.utils.KotlinPaths
import org.jetbrains.kotlin.utils.join
import java.io.File
import java.io.IOException
import java.util.*
import java.util.zip.ZipFile

class K2JsIrCompiler : CLICompiler<K2JSCompilerArguments>() {

    override val performanceManager: CommonCompilerPerformanceManager =
        object : CommonCompilerPerformanceManager("Kotlin to JS (IR) Compiler") {}

    override fun createArguments(): K2JSCompilerArguments {
        return K2JSCompilerArguments()
    }

    fun doExecuteFromLegacyCli(
        arguments: K2JSCompilerArguments,
        configuration: CompilerConfiguration,
        rootDisposable: Disposable,
        paths: KotlinPaths?
    ): ExitCode {
        return doExecute(arguments, configuration, rootDisposable, paths)
    }

    override fun doExecute(
        arguments: K2JSCompilerArguments,
        configuration: CompilerConfiguration,
        rootDisposable: Disposable,
        paths: KotlinPaths?
    ): ExitCode {
        val messageCollector = configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)

        messageCollector.report(STRONG_WARNING, "IR Cli: Free args: ${arguments.freeArgs.joinToString("  ")}")
        messageCollector.report(STRONG_WARNING, "IR Cli: Libraries: ${arguments.libraries}")

        if (arguments.freeArgs.isEmpty() && !IncrementalCompilation.isEnabledForJs()) {
            if (arguments.version) {
                return OK
            }
            messageCollector.report(ERROR, "Specify at least one source file or directory", null)
            return COMPILATION_ERROR
        }

        val pluginLoadResult = PluginCliParser.loadPluginsSafe(
            arguments.pluginClasspaths,
            arguments.pluginOptions,
            configuration
        )
        if (pluginLoadResult != ExitCode.OK) return pluginLoadResult

        // val stdlibPath = "/Users/jetbrains/work/kotlin/compiler/ir/serialization.js/build/fullRuntime/klib"
        val libraries =
                // listOf(stdlibPath) +
                configureLibraries(arguments.libraries).filter { !it.contains(Regex("kotlin-stdlib-(js|common)-1.3.*\\.jar")) }

        // val allLibraries = (configureLibraries(arguments.allLibraries) + libraries).distinct()
        configuration.put(JSConfigurationKeys.LIBRARIES, configureLibraries(arguments.libraries))
        configuration.put(JSConfigurationKeys.TRANSITIVE_LIBRARIES, configureLibraries(arguments.libraries))

        val commonSourcesArray = arguments.commonSources
        val commonSources = commonSourcesArray?.let { setOf(it) } ?: emptySet()
        for (arg in arguments.freeArgs) {
            configuration.addKotlinSourceRoot(arg, commonSources.contains(arg))
        }

        val environmentForJS =
            KotlinCoreEnvironment.createForProduction(rootDisposable, configuration, EnvironmentConfigFiles.JS_CONFIG_FILES)

        val project = environmentForJS.project
        val sourcesFiles = environmentForJS.getSourceFiles()

        environmentForJS.configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, arguments.allowKotlinPackage)

        if (!checkKotlinPackageUsage(environmentForJS, sourcesFiles)) return ExitCode.COMPILATION_ERROR

        val outputFilePath = arguments.outputFile
        if (outputFilePath == null) {
            messageCollector.report(ERROR, "IR: Specify output file via -output", null)
            return ExitCode.COMPILATION_ERROR
        }

        if (messageCollector.hasErrors()) {
            return ExitCode.COMPILATION_ERROR
        }

        if (sourcesFiles.isEmpty() && !IncrementalCompilation.isEnabledForJs()) {
            messageCollector.report(ERROR, "No source files", null)
            return COMPILATION_ERROR
        }

        if (arguments.verbose) {
            reportCompiledSourcesList(messageCollector, sourcesFiles)
        }

        val outputFile = File(outputFilePath)

        configuration.put(CommonConfigurationKeys.MODULE_NAME, FileUtil.getNameWithoutExtension(outputFile))

        val config = JsConfig(project, configuration)
        val outputDir: File = outputFile.parentFile ?: outputFile.absoluteFile.parentFile!!
        try {
            config.configuration.put(JSConfigurationKeys.OUTPUT_DIR, outputDir.canonicalFile)
        } catch (e: IOException) {
            messageCollector.report(ERROR, "Could not resolve output directory", null)
            return ExitCode.COMPILATION_ERROR
        }

        // TODO: Handle
        val mainCallParameters = createMainCallParameters(arguments.main)

        val metadataExtension = JsKlibMetadataSerializationUtil.CLASS_METADATA_FILE_EXTENSION


        val dependencies = libraries.map { library ->
            var klibDir = File(library)
            when {
                FileUtil.isJarOrZip(klibDir) -> {
                    val zipLib = ZipFile(library)
                    val tempDir = createTempDir(klibDir.name, "klibjar")
//                    messageCollector.report(STRONG_WARNING, "Created tmpDirectory: $tempDir")
                    val entries = zipLib.entries().toList()
//                    messageCollector.report(STRONG_WARNING, "Zip entries: ${entries.joinToString(",") { it.name }}")

                    var extractedKlibDir: File? = null
                    entries.forEach { entry ->
                        if (!entry.isDirectory) {
//                            messageCollector.report(STRONG_WARNING, "       Zip entry  -- ${entry.name}")
                            zipLib.getInputStream(entry).use { input ->
                                val outputEntryFile = File(tempDir, entry.name)
                                outputEntryFile.parentFile.mkdirs()
                                outputEntryFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        } else {
//                            messageCollector.report(STRONG_WARNING, "       Directory  -- ${entry.name}")
                            if (entry.name.endsWith("KLIB/")) {
                                extractedKlibDir = File(tempDir, entry.name)
                                messageCollector.report(STRONG_WARNING, "Klib $library is extracted into $extractedKlibDir")
                            }
                        }
                    }

                    if (extractedKlibDir == null) {
                        messageCollector.report(ERROR, "Klib $library must be a directory")
                        return ExitCode.COMPILATION_ERROR
                    }
                    klibDir = extractedKlibDir!!
                }
                !klibDir.isDirectory -> {
                    messageCollector.report(ERROR, "Klib $library must be a directory")
                    return ExitCode.COMPILATION_ERROR
                }
            }

//            messageCollector.report(STRONG_WARNING, "       Klib dir  -- ${klibDir}")

            val klibFiles = klibDir.listFiles()
            if (klibFiles.isEmpty()) {
                messageCollector.report(ERROR, "Klib $library directory is empty")
                return ExitCode.COMPILATION_ERROR
            }

            val metadataFile = klibFiles.find {
                it.extension == metadataExtension
            }

            if (metadataFile == null) {
                messageCollector.report(ERROR, "Couldn't find find metadata file (.$metadataExtension) for klib: $library")
                return ExitCode.COMPILATION_ERROR
            }
            KlibModuleRef(metadataFile.nameWithoutExtension, klibDir.absolutePath)
        }

        val compiledModule = compile(
            project,
            sourcesFiles,
            configuration,
            immediateDependencies = dependencies,
            allDependencies = dependencies
        )

        outputFile.writeText(compiledModule)

        val outputKlibPath = "$outputFilePath.KLIB"

        if (arguments.metaInfo) {
            generateKLib(
                project = config.project,
                files = sourcesFiles,
                configuration = config.configuration,
                immediateDependencies = dependencies,
                allDependencies = dependencies,
                outputKlibPath = outputKlibPath
            )
        }

        return OK
    }

    fun setupPlatformSpecificArgumentsAndServicesFromLegacyCli(
        configuration: CompilerConfiguration,
        arguments: K2JSCompilerArguments,
        services: Services
    ) {
        setupPlatformSpecificArgumentsAndServices(configuration, arguments, services)
    }

    override fun setupPlatformSpecificArgumentsAndServices(
        configuration: CompilerConfiguration,
        arguments: K2JSCompilerArguments,
        services: Services
    ) {
        val messageCollector = configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)

        if (arguments.target != null) {
            assert("v5" == arguments.target) { "Unsupported ECMA version: " + arguments.target!! }
        }
        configuration.put(JSConfigurationKeys.TARGET, EcmaVersion.defaultVersion())

        // TODO: Support source maps
        if (arguments.sourceMap) {
            messageCollector.report(WARNING, "source-map argument is not supported yet", null)
        } else {
            if (arguments.sourceMapPrefix != null) {
                messageCollector.report(WARNING, "source-map-prefix argument has no effect without source map", null)
            }
            if (arguments.sourceMapBaseDirs != null) {
                messageCollector.report(WARNING, "source-map-source-root argument has no effect without source map", null)
            }
        }

        if (arguments.metaInfo) {
            configuration.put(JSConfigurationKeys.META_INFO, true)
        }

        configuration.put(JSConfigurationKeys.TYPED_ARRAYS_ENABLED, arguments.typedArrays)

        configuration.put(JSConfigurationKeys.FRIEND_PATHS_DISABLED, arguments.friendModulesDisabled)

        val friendModules = arguments.friendModules
        if (!arguments.friendModulesDisabled && friendModules != null) {
            val friendPaths = friendModules
                .split(File.pathSeparator.toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()
                .filterNot { it.isEmpty() }

            configuration.put(JSConfigurationKeys.FRIEND_PATHS, friendPaths)
        }

        val moduleKindName = arguments.moduleKind
        var moduleKind: ModuleKind? = if (moduleKindName != null) moduleKindMap[moduleKindName] else ModuleKind.PLAIN
        if (moduleKind == null) {
            messageCollector.report(
                ERROR, "Unknown module kind: $moduleKindName. Valid values are: plain, amd, commonjs, umd", null
            )
            moduleKind = ModuleKind.PLAIN
        }
        configuration.put(JSConfigurationKeys.MODULE_KIND, moduleKind)

        val incrementalDataProvider = services[IncrementalDataProvider::class.java]
        if (incrementalDataProvider != null) {
            configuration.put(JSConfigurationKeys.INCREMENTAL_DATA_PROVIDER, incrementalDataProvider)
        }

        val incrementalResultsConsumer = services[IncrementalResultsConsumer::class.java]
        if (incrementalResultsConsumer != null) {
            configuration.put(JSConfigurationKeys.INCREMENTAL_RESULTS_CONSUMER, incrementalResultsConsumer)
        }

        val lookupTracker = services[LookupTracker::class.java]
        if (lookupTracker != null) {
            configuration.put(CommonConfigurationKeys.LOOKUP_TRACKER, lookupTracker)
        }

        val expectActualTracker = services[ExpectActualTracker::class.java]
        if (expectActualTracker != null) {
            configuration.put(CommonConfigurationKeys.EXPECT_ACTUAL_TRACKER, expectActualTracker)
        }

        val sourceMapEmbedContentString = arguments.sourceMapEmbedSources
        var sourceMapContentEmbedding: SourceMapSourceEmbedding? = if (sourceMapEmbedContentString != null)
            sourceMapContentEmbeddingMap[sourceMapEmbedContentString]
        else
            SourceMapSourceEmbedding.INLINING
        if (sourceMapContentEmbedding == null) {
            val message = "Unknown source map source embedding mode: " + sourceMapEmbedContentString + ". Valid values are: " +
                    StringUtil.join(sourceMapContentEmbeddingMap.keys, ", ")
            messageCollector.report(ERROR, message, null)
            sourceMapContentEmbedding = SourceMapSourceEmbedding.INLINING
        }
        configuration.put(JSConfigurationKeys.SOURCE_MAP_EMBED_SOURCES, sourceMapContentEmbedding)

        if (!arguments.sourceMap && sourceMapEmbedContentString != null) {
            messageCollector.report(WARNING, "source-map-embed-sources argument has no effect without source map", null)
        }
    }

    override fun executableScriptFileName(): String {
        return "kotlinc-js-ir"
    }

    override fun createMetadataVersion(versionArray: IntArray): BinaryVersion {
        return JsMetadataVersion(*versionArray)
    }

    companion object {
        private val moduleKindMap = HashMap<String, ModuleKind>()
        private val sourceMapContentEmbeddingMap = LinkedHashMap<String, SourceMapSourceEmbedding>()

        init {
            moduleKindMap[K2JsArgumentConstants.MODULE_PLAIN] = ModuleKind.PLAIN
            moduleKindMap[K2JsArgumentConstants.MODULE_COMMONJS] = ModuleKind.COMMON_JS
            moduleKindMap[K2JsArgumentConstants.MODULE_AMD] = ModuleKind.AMD
            moduleKindMap[K2JsArgumentConstants.MODULE_UMD] = ModuleKind.UMD

            sourceMapContentEmbeddingMap[K2JsArgumentConstants.SOURCE_MAP_SOURCE_CONTENT_ALWAYS] = SourceMapSourceEmbedding.ALWAYS
            sourceMapContentEmbeddingMap[K2JsArgumentConstants.SOURCE_MAP_SOURCE_CONTENT_NEVER] = SourceMapSourceEmbedding.NEVER
            sourceMapContentEmbeddingMap[K2JsArgumentConstants.SOURCE_MAP_SOURCE_CONTENT_INLINING] = SourceMapSourceEmbedding.INLINING
        }

        @JvmStatic
        fun main(args: Array<String>) {
            CLITool.doMain(K2JsIrCompiler(), args)
        }

        private fun reportCompiledSourcesList(messageCollector: MessageCollector, sourceFiles: List<KtFile>) {
            val fileNames = sourceFiles.map { file ->
                val virtualFile = file.virtualFile
                if (virtualFile != null) {
                    MessageUtil.virtualFileToPath(virtualFile)
                } else {
                    file.name + " (no virtual file)"
                }
            }
            messageCollector.report(LOGGING, "Compiling source files: " + join(fileNames, ", "), null)
        }

        private fun configureLibraries(
            libraryString: String?
        ): List<String> {
            val libraries = SmartList<String>()
            if (libraryString != null) {
                libraries.addAll(
                    libraryString
                        .split(File.pathSeparator.toRegex())
                        .dropLastWhile { it.isEmpty() }
                        .toTypedArray()
                        .filterNot { it.isEmpty() }
                )
            }

            return libraries
        }

        private fun createMainCallParameters(main: String?): MainCallParameters {
            return if (K2JsArgumentConstants.NO_CALL == main) {
                MainCallParameters.noCall()
            } else {
                MainCallParameters.mainWithoutArguments()
            }
        }
    }
}
