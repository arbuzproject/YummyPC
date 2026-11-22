plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    application
}

application {
    mainClass.set("su.afk.yummy.pc.player.ipc.MpvIpcCliKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // StateFlow в публичном API контроллера — поэтому api, а не implementation.
    api(libs.kotlinx.coroutines.core)
    // JsonElement в MpvCommandResult/MpvMessage — тоже часть публичного контракта.
    api(libs.kotlinx.serialization.json)
    // JNA нужен только внутри (overlapped I/O к именованному каналу) — implementation.
    implementation(libs.jna)

    testImplementation(kotlin("test-junit"))
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
