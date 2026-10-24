plugins {
    `java-library`
}

description = "Zelus patches applied on top of RuneLite's injected client"

dependencies {
    implementation("net.runelite:runelite-api:${project.version}")
    implementation("org.json:json:20240303")
    implementation("org.ow2.asm:asm-tree:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
    implementation("org.ow2.asm:asm-util:9.7")
    implementation("org.ow2.asm:asm:9.7")
    implementation("net.runelite.archive-patcher:archive-patcher-applier:1.2")
}

// Runs only at build time (generateInjectedData), on Gradle's own JDK; uses Java 16+ syntax.
tasks.withType<JavaCompile> {
    options.release = 17
}
