plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":compiler:backend"))
    compile(project(":idea:idea-core"))
    testCompile(project(":kotlin-test:kotlin-test-junit"))

    // TODO: get rid of this
    compile(project(":idea:jvm-debugger:eval4j"))

    testCompile(commonDep("junit:junit"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { none() }
}