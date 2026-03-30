import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    application
}

group = "com.atomengine"
version = "1.0.0"

repositories {
    mavenCentral()
}

// LWJGL platform detection
val lwjglVersion = "3.3.3"
val jomlVersion = "1.10.5"
val imguiVersion = "1.86.11"

val lwjglNatives = when {
    System.getProperty("os.name").startsWith("Windows") -> "natives-windows"
    System.getProperty("os.name").startsWith("Mac")     -> {
        if (System.getProperty("os.arch").contains("aarch64")) "natives-macos-arm64"
        else "natives-macos"
    }
    else -> "natives-linux"
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))

    // LWJGL 3 core + OpenGL + GLFW + STB
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-stb")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglNatives")

    // JOML (Java OpenGL Math Library)
    implementation("org.joml:joml:$jomlVersion")

    // ImGui-Java (imgui-java binding)
    implementation("io.github.spair:imgui-java-binding:$imguiVersion")
    implementation("io.github.spair:imgui-java-lwjgl3:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-windows:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-linux:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-macos:$imguiVersion")
}

application {
    mainClass.set("com.atomengine.MainKt")
    // Required for LWJGL on macOS
    applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks.withType<JavaExec> {
    jvmArgs("-XstartOnFirstThread")
}

// Fat JAR task for distribution
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.atomengine.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
