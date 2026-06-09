import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.jvm)
    kotlin("kapt") version "2.3.20"
    `maven-publish`
}

group = "org.punkrecordz"
version = "1.0.0"

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "totem"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            val repository = System.getenv("GITHUB_REPOSITORY") ?: "punkrecordz/totem"
            url = uri("https://maven.pkg.github.com/$repository")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io/") }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.jlibdeflate)

    testImplementation(libs.querz.nbt)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotest.property)
    testImplementation(libs.jmh.core)
    kaptTest(libs.jmh.generator)
}

fun getPlatformDirectoryName(): String {
    val operatingSystem = OperatingSystem.current()
    val architecture = System.getProperty("os.arch").lowercase()

    val osName = when {
        operatingSystem.isWindows -> "windows"
        operatingSystem.isMacOsX -> "macos"
        operatingSystem.isLinux -> "linux"
        else -> throw GradleException("Unsupported OS: ${operatingSystem.name}")
    }

    val archName = when (architecture) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> throw GradleException("Unsupported architecture: $architecture")
    }

    return "${osName}_${archName}"
}

fun getLibraryFileName(): String {
    val operatingSystem = OperatingSystem.current()
    return when {
        operatingSystem.isWindows -> "totem_sys.dll"
        operatingSystem.isMacOsX -> "libtotem_sys.dylib"
        operatingSystem.isLinux -> "libtotem_sys.so"
        else -> throw GradleException("Unsupported OS")
    }
}

val compileRust = tasks.register<Exec>("compileRust") {
    workingDir = file("native")
    commandLine("cargo", "build", "--release")
}

val copyNativeLibrary = tasks.register<Copy>("copyNativeLibrary") {
    dependsOn(compileRust)
    val platformDir = getPlatformDirectoryName()
    val libName = getLibraryFileName()
    from(file("native/target/release/$libName"))
    into(file("src/main/resources/natives/$platformDir"))
}

tasks.processResources {
    dependsOn(copyNativeLibrary)
}

tasks.named<Jar>("sourcesJar") {
    dependsOn(copyNativeLibrary)
}

tasks.test {
    dependsOn("compileRust")
    useJUnitPlatform()
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.foreign=ALL-UNNAMED",
        "--add-modules", "jdk.incubator.vector"
    )
}

tasks.register<JavaExec>("runJmh") {
    dependsOn("compileRust")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "org.punkrecordz.totem.JmhBenchmarks"
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.foreign=ALL-UNNAMED",
        "--add-modules", "jdk.incubator.vector",
        "-Xms6g",
        "-Xmx8g",
    )
}

tasks.register<JavaExec>("runVarIntJmh") {
    dependsOn("compileRust")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "org.punkrecordz.totem.VarIntBenchmarks"
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.foreign=ALL-UNNAMED",
        "--add-modules", "jdk.incubator.vector",
        "-Xms6g",
        "-Xmx8g"
    )
}

kotlin {
    jvmToolchain(24)
}




