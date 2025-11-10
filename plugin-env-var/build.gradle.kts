import org.jetbrains.intellij.tasks.RunIdeTask
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.JavaVersion
import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("org.jetbrains.intellij")
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = "com.example.pluginenvvar"
version = "0.1.1"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.2")
    type.set("IC")
    plugins.set(listOf("com.intellij.java"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

tasks.patchPluginXml {
    version.set(project.version.toString())
    sinceBuild.set("232")
    untilBuild.set(null as String?)
    changeNotes.set("Initial environment variable tool window preview.")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
}

tasks.withType<RunIdeTask>().configureEach {
    systemProperty("PLUGIN_ENV_VAR_DEBUG", "true")
}

val webviewDist = rootProject.layout.projectDirectory.dir("webview-ui/dist")

tasks.named<ProcessResources>("processResources") {
    dependsOn(rootProject.tasks.named("webviewBuild"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(webviewDist) {
        into("webview")
    }
}

sourceSets {
    main {
        java.srcDirs("src/main/kotlin")
        resources.srcDirs("src/main/resources")
    }
    test {
        java.srcDirs("src/test/kotlin")
    }
}
