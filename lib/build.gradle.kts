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

tasks.test {
    useTestNG {
        preserveOrder = true
    }
    testLogging {
        showStandardStreams = true
    }
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