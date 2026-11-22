plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("su.afk.yummy.pc.api.SmokeKt")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
    api(libs.ktor.client.contentNegotiation)
    api(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
}
