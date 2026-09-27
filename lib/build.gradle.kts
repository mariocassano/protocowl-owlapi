plugins {
    `java-library`
    eclipse
}

repositories {
    mavenCentral()
}

dependencies {
    api("net.sourceforge.owlapi:owlapi-distribution:5.5.1")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("org.testng:testng:7.11.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "4g"
    providers.gradleProperty("datasetDir").orElse(providers.environmentVariable("DATASET_DIR")).orNull?.let {
        systemProperty("dataset.dir", rootProject.file(it).absolutePath)
    }
    providers.gradleProperty("metadataFile").orElse(providers.environmentVariable("METADATA_FILE")).orNull?.let {
        systemProperty("metadata.file", rootProject.file(it).absolutePath)
    }
    testLogging { events("failed", "skipped") }
}

tasks.test {
    useTestNG { excludeGroups("dataset", "coverage") }
}

tasks.register<Test>("coverageTest") {
    description = "Verify every dataset input and standard rendering; write coverage_report.csv."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useTestNG { includeGroups("coverage") }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("datasetTest") {
    description = "Verify all 200 dataset round trips (phases a, b and c)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useTestNG { includeGroups("dataset") }
    mustRunAfter("coverageTest")
    outputs.upToDateWhen { false }
}

// Workaround for: https://github.com/redhat-developer/vscode-java/issues/1615
eclipse {
    classpath {
        baseSourceOutputDir = file("build")
    }
}

tasks.register<Copy>("copyDependencies") {
    from(configurations.testRuntimeClasspath)
    into("build/libs/dependencies")
}