plugins {
    kotlin("jvm") version "2.4.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "dev.daika"
version = "1.0.0"

repositories {
    mavenCentral()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("DAVY Parsers")
        description.set("A library for parsing player data from various sources.")
        url.set("https://github.com/AsfhtgkDavid/davy-parsers")

        licenses {
            license {
                name.set("Affero General Public License v3.0")
                url.set("https://www.gnu.org/licenses/agpl-3.0.html")
            }
        }
        developers {
            developer {
                id.set("AsfhtgkDavid")
                name.set("David Lishchyshen")
                email.set("david@daika.dev")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/AsfhtgkDavid/davy-parsers.git")
            developerConnection.set("scm:git:ssh://github.com/AsfhtgkDavid/davy-parsers.git")
            url.set("https://github.com/AsfhtgkDavid/davy-parsers")
        }
    }
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