plugins {
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Print Hebrew and other non-ASCII text correctly in the IntelliJ run console.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// A runnable jar: build/libs/WorkerDocMaker.jar (java -jar WorkerDocMaker.jar). The project has no runtime dependencies.
tasks.jar {
    archiveFileName = "WorkerDocMaker.jar"
    manifest {
        attributes["Main-Class"] = "Main"
    }
}
