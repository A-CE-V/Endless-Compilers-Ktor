plugins {
    kotlin("jvm") version "2.0.20"

    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.ktor.plugin") version "2.3.12"
}

group = "org.endless-forge"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}


application {
    mainClass.set("org.endless.Mainkt")
}

dependencies {

    // --- Ktor Core ---
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-jackson-jvm") // For JSON responses
    implementation("io.ktor:ktor-server-status-pages-jvm") // Error handling
    implementation("ch.qos.logback:logback-classic:1.4.14") // Logging

    // --- Decompilers (Same as your old POM) ---
    implementation("org.benf:cfr:0.152")
    implementation("org.bitbucket.mstrobel:procyon-compilertools:0.6.0")
    implementation("io.github.skylot:jadx-core:1.5.3")
    implementation("io.github.skylot:jadx-java-input:1.5.3")

    // --- Utils ---
    implementation("commons-io:commons-io:2.11.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}