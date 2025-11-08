import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.intellij") version "1.17.3" apply false
    kotlin("jvm") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}

repositories {
    mavenCentral()
}

subprojects {
    repositories {
        mavenCentral()
    }

    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "17"
    }
}

val webviewDir = projectDir.resolve("webview-ui")

val webviewNpmInstall = tasks.register<Exec>("webviewNpmInstall") {
    group = "webview"
    description = "Install npm dependencies for the webview UI"
    workingDir = webviewDir
    commandLine = listOf("npm", "ci")
    onlyIf { webviewDir.resolve("package-lock.json").exists() }
}

val webviewBuild = tasks.register<Exec>("webviewBuild") {
    group = "webview"
    description = "Build the webview UI via Vite"
    workingDir = webviewDir
    dependsOn(webviewNpmInstall)
    commandLine = listOf("npm", "run", "build")
    inputs.dir(webviewDir.resolve("src"))
    inputs.file(webviewDir.resolve("package.json"))
    outputs.dir(webviewDir.resolve("dist"))
    onlyIf { webviewDir.exists() }
}

val aggregateBuildPlugin = tasks.register("buildPlugin") {
    group = "build"
    description = "Aggregate build for the IntelliJ plugin"
    dependsOn(webviewBuild)
    dependsOn(":plugin-env-var:buildPlugin")
}

tasks.register("runIde") {
    group = "run"
    description = "Launch the sandboxed IDE with the plugin"
    dependsOn(":plugin-env-var:runIde")
}

tasks.register("check") {
    group = "verification"
    description = "Aggregate verification for Kotlin and frontend code"
    dependsOn(":plugin-env-var:check")
    dependsOn(webviewBuild)
}
