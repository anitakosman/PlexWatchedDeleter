import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.20"
    application
}

group = "me.anita"
version = "1.0"

repositories {
    mavenCentral()
    maven { url = uri("https://dl.bintray.com/kotlin/kotlinx") }
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17+")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17+")
    implementation("com.sksamuel.hoplite:hoplite-core:2.7.5")
    implementation("com.sksamuel.hoplite:hoplite-yaml:2.7.5")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.sksamuel.hoplite:hoplite-watch:2.7.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")
}

tasks.withType<KotlinCompile> {
    compilerOptions.jvmTarget.set(JVM_21)
}

tasks.withType<JavaCompile> {
    targetCompatibility = "21"
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    configurations["compileClasspath"].forEach { file: File ->
        from(zipTree(file.absoluteFile))
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

application {
    mainClass.set("MainKt")
}
