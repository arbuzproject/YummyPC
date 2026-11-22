plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("su.afk.yummy.pc.extractors.KodikCliKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    // org.json — как org.json.JSONObject в Android-оригинале; в version catalog не заведён.
    implementation("org.json:json:20250517")

    testImplementation(kotlin("test-junit"))
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
