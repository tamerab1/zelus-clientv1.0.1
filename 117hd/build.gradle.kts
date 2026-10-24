plugins {
    `java-library`
    alias(libs.plugins.lombok)
}

description = "117 HD Plugin"

lombok.version = libs.versions.lombok.get()

dependencies {
    api("net.runelite:runelite-api:${project.version}")
    api(project(":client"))

    compileOnly(libs.findbugs)
    compileOnly(libs.jetbrains.annotations)

    api(libs.gson)
    api(variantOf(libs.guice.core) { classifier("no_aop") })
    api(libs.lwjgl.core)
    api(libs.lwjgl.opengl)
    api(libs.lwjgl.opencl)
    for (platform in listOf("windows", "windows-x86", "windows-arm64")) {
        runtimeOnly(variantOf(libs.lwjgl.core) { classifier("natives-$platform") })
        runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier("natives-$platform") })
    }
}
