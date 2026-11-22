import java.io.File as JavaFile
import java.util.Properties as JavaProperties

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

// Адрес сервера для релизной сборки берётся из локального `pc/secrets.properties`
// (файл в .gitignore, в репозиторий не попадает) или из `-PyummypcBackend=...`.
// В исходниках и публичном репозитории адреса нет, а в собранный клиент он зашивается.
val defaultsOutputDir = layout.buildDirectory.dir("generated/defaults")
val generateBuildDefaults = tasks.register("generateBuildDefaults") {
    val outDir = defaultsOutputDir
    val secretFile = rootProject.file("secrets.properties")
    val gradleValue = providers.gradleProperty("yummypcBackend").orElse("")
    inputs.file(secretFile).optional().withPropertyName("secrets")
    inputs.property("gradleBackend", gradleValue)
    outputs.dir(outDir)
    doLast {
        val fromSecret = if (secretFile.exists()) {
            JavaProperties().apply {
                secretFile.inputStream().use { load(it) }
            }.getProperty("backendUrl").orEmpty()
        } else {
            ""
        }
        val backend = gradleValue.get().ifBlank { fromSecret }.trim().trimEnd('/')
        val escaped = backend.replace("\\", "\\\\").replace("\"", "\\\"")
        val dir = outDir.get().asFile
        dir.mkdirs()
        JavaFile(dir, "BuildDefaults.kt").writeText(
            """
            // Сгенерировано сборкой; адрес сервера задаётся в pc/secrets.properties.
            package su.afk.yummy.pc.api

            internal object BuildDefaults {
                const val BACKEND_URL: String = "$escaped"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(generateBuildDefaults)

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
