plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
}

group = "com.example.util.simpletimetracker.desktop"
version = "0.1.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    implementation("com.dorkbox:SystemTray:4.4")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "com.example.util.simpletimetracker.desktop.MainKt"
    }
}
