plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "dev.daika"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}