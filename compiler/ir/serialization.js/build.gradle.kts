plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":compiler:ir.psi2ir"))
    compile(project(":compiler:ir.serialization.common"))
    compile(project(":js:js.frontend"))

    compileOnly(intellijCoreDep()) { includeJars("intellij-core") }

    testCompile(projectTests(":compiler:tests-common"))
    testCompileOnly(project(":compiler:frontend"))
    testCompileOnly(project(":compiler:cli"))
    testCompileOnly(project(":compiler:util"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

val unimplementedNativeBuiltIns =
    (file("$rootDir/core/builtins/native/kotlin/").list().toSet() - file("$rootDir/libraries/stdlib/js-ir/builtins/").list())
        .map { "core/builtins/native/kotlin/$it" }

// Required to compile native builtins with the rest of runtime
val builtInsHeader = """@file:Suppress(
    "NON_ABSTRACT_FUNCTION_WITH_NO_BODY",
    "MUST_BE_INITIALIZED_OR_BE_ABSTRACT",
    "EXTERNAL_TYPE_EXTENDS_NON_EXTERNAL_TYPE",
    "PRIMARY_CONSTRUCTOR_DELEGATION_CALL_EXPECTED",
    "WRONG_MODIFIER_TARGET"
)
"""

val runtimeSourcesDestinationPath = "$buildDir/fullRuntime/src"
val reducedRuntimeSourcesDestinationPath ="$buildDir/reducedRuntime/src"

val fullRuntimeSources by task<Copy> {

    doFirst {
        delete(runtimeSourcesDestinationPath)
    }

    val sources = listOf(
        "core/builtins/src/kotlin/",
        "libraries/stdlib/common/src/",
        "libraries/stdlib/src/kotlin/",
        "libraries/stdlib/unsigned/",
        "libraries/stdlib/js-common/src/",
        "libraries/stdlib/js-common/runtime/",
        "libraries/stdlib/js-ir/builtins/",
        "libraries/stdlib/js-ir/src/",
        "libraries/stdlib/js-ir/runtime/",

        // TODO get rid - move to test module
        "js/js.translator/testData/_commonFiles/"
    ) + unimplementedNativeBuiltIns

    val excluded = listOf(
        "libraries/stdlib/common/src/kotlin/JvmAnnotationsH.kt",
        "libraries/stdlib/src/kotlin/annotations/Multiplatform.kt",
        "libraries/stdlib/common/src/kotlin/NativeAnnotationsH.kt",
        
        // Fails with: EXPERIMENTAL_IS_NOT_ENABLED
        "libraries/stdlib/common/src/kotlin/annotations/Annotations.kt",

        // JS-specific optimized version of emptyArray() already defined
        "core/builtins/src/kotlin/ArrayIntrinsics.kt",

        // Expect declarations get thrown away and libraries/kotlin.test/common/src/main/kotlin/kotlin/test/Assertions.kt doesn't compile
        "libraries/stdlib/common/src/kotlin/NativeAnnotationsH.kt"
    )

    sources.forEach { path ->
        from("$rootDir/$path") {
            into(path.dropLastWhile { it != '/' })
            excluded.filter { it.startsWith(path) }.forEach {
                exclude(it.substring(path.length))
            }
        }
    }

    into(runtimeSourcesDestinationPath)

    doLast {
        unimplementedNativeBuiltIns.forEach { path ->
            val file = File("$buildDir/fullRuntime/src/$path")
            val sourceCode = builtInsHeader + file.readText()
            file.writeText(sourceCode)
        }
    }
}

val reducedRuntimeSources by task<Copy> {
    dependsOn(fullRuntimeSources)

    doFirst {
        delete(reducedRuntimeSourcesDestinationPath)
    }

    from(fullRuntimeSources.outputs.files.singleFile) {
        exclude(
            listOf(
                "libraries/stdlib/unsigned/**",
                "libraries/stdlib/common/src/generated/_Arrays.kt",
                "libraries/stdlib/common/src/generated/_Collections.kt",
                "libraries/stdlib/common/src/generated/_Comparisons.kt",
                "libraries/stdlib/common/src/generated/_Maps.kt",
                "libraries/stdlib/common/src/generated/_Sequences.kt",
                "libraries/stdlib/common/src/generated/_Sets.kt",
                "libraries/stdlib/common/src/generated/_Strings.kt",
                "libraries/stdlib/common/src/generated/_UArrays.kt",
                "libraries/stdlib/common/src/generated/_URanges.kt",
                "libraries/stdlib/common/src/generated/_UCollections.kt",
                "libraries/stdlib/common/src/generated/_UComparisons.kt",
                "libraries/stdlib/common/src/generated/_USequences.kt",
                "libraries/stdlib/common/src/kotlin/SequencesH.kt",
                "libraries/stdlib/common/src/kotlin/TextH.kt",
                "libraries/stdlib/common/src/kotlin/UMath.kt",
                "libraries/stdlib/common/src/kotlin/collections/**",
                "libraries/stdlib/common/src/kotlin/ioH.kt",
                "libraries/stdlib/js-ir/runtime/collectionsHacks.kt",
                "libraries/stdlib/js-ir/src/generated/**",
                "libraries/stdlib/js-common/src/jquery/**",
                "libraries/stdlib/js-common/src/org.w3c/**",
                "libraries/stdlib/js-common/src/kotlin/char.kt",
                "libraries/stdlib/js-common/src/kotlin/collections.kt",
                "libraries/stdlib/js-common/src/kotlin/collections/**",
                "libraries/stdlib/js-common/src/kotlin/console.kt",
                "libraries/stdlib/js-common/src/kotlin/coreDeprecated.kt",
                "libraries/stdlib/js-common/src/kotlin/date.kt",
                "libraries/stdlib/js-common/src/kotlin/debug.kt",
                "libraries/stdlib/js-common/src/kotlin/grouping.kt",
                "libraries/stdlib/js-common/src/kotlin/json.kt",
                "libraries/stdlib/js-common/src/kotlin/numberConversions.kt",
                "libraries/stdlib/js-common/src/kotlin/promise.kt",
                "libraries/stdlib/js-common/src/kotlin/regex.kt",
                "libraries/stdlib/js-common/src/kotlin/regexp.kt",
                "libraries/stdlib/js-common/src/kotlin/sequence.kt",
                "libraries/stdlib/js-common/src/kotlin/string.kt",
                "libraries/stdlib/js-common/src/kotlin/stringsCode.kt",
                "libraries/stdlib/js-common/src/kotlin/text.kt",
                "libraries/stdlib/src/kotlin/collections/**",
                "libraries/stdlib/src/kotlin/experimental/bitwiseOperations.kt",
                "libraries/stdlib/src/kotlin/properties/Delegates.kt",
                "libraries/stdlib/src/kotlin/random/URandom.kt",
                "libraries/stdlib/src/kotlin/text/**",
                "libraries/stdlib/src/kotlin/util/KotlinVersion.kt",
                "libraries/stdlib/src/kotlin/util/Tuples.kt",
                "libraries/stdlib/js-common/src/kotlin/dom/**",
                "libraries/stdlib/js-common/src/kotlin/browser/**"
            )
        )
    }

    from("$rootDir/libraries/stdlib/js-ir/smallRuntime") {
        into("libraries/stdlib/js-ir/runtime/")
    }

    into(reducedRuntimeSourcesDestinationPath)
}


fun JavaExec.buildKLib(sources: List<String>, dependencies: List<String>, outPath: String) {
    inputs.files(sources)
    outputs.dir(file(outPath).parent)

    classpath = sourceSets.test.get().runtimeClasspath
    main = "org.jetbrains.kotlin.ir.backend.js.GenerateIrRuntimeKt"
    workingDir = rootDir
    args = sources.toList() + listOf("-o", outPath) + dependencies.flatMap { listOf("-d", it) }

    passClasspathInJar()
}

val generateFullRuntimeKLib by task<NoDebugJavaExec> {
    dependsOn(fullRuntimeSources)

    buildKLib(sources = listOf(fullRuntimeSources.outputs.files.singleFile.path),
              dependencies = emptyList(),
              outPath = "$buildDir/fullRuntime/klib/JS_IR_RUNTIME.klm")
}

val generateReducedRuntimeKLib by task<NoDebugJavaExec> {
    dependsOn(reducedRuntimeSources)

    buildKLib(sources = listOf(reducedRuntimeSources.outputs.files.singleFile.path),
              dependencies = emptyList(),
              outPath = "$buildDir/reducedRuntime/klib/JS_IR_RUNTIME.klm")
}

val generateKotlinTestKLib by task<NoDebugJavaExec> {
    dependsOn(generateFullRuntimeKLib)

    buildKLib(
        sources = listOf(
            "$rootDir/libraries/kotlin.test/annotations-common/src/main",
            "$rootDir/libraries/kotlin.test/common/src/main",
            "$rootDir/libraries/kotlin.test/js/src/main"
        ),
        dependencies = listOf("${generateFullRuntimeKLib.outputs.files.singleFile.path}/JS_IR_RUNTIME.klm"),
        outPath = "$buildDir/kotlin.test/klib/kotlin.test.klm"
    )
}

testsJar {}
