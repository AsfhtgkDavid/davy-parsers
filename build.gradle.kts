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
    api(platform("com.squareup.okhttp3:okhttp-bom:5.4.0"))
    api("com.squareup.okhttp3:okhttp")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jsoup:jsoup:1.22.2")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}