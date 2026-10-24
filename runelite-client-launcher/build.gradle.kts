plugins {
    java
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
}

application {
    mainClass = "runelite.launcher.Launcher"
}

tasks.shadowJar {
    isZip64 = true
    minimize()
    // release CI copies runelite-client-launcher-all.jar
    archiveFileName = "runelite-client-launcher-all.jar"
}

tasks.register<JavaExec>("launcherRun") {
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = rootDir
    mainClass = "runelite.launcher.Launcher"
}
