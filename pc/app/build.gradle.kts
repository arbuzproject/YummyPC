import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":extractors"))
    implementation(project(":player-ipc"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)
}

compose.desktop {
    application {
        mainClass = "su.afk.yummy.pc.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "YummyPC"
            packageVersion = "1.5.0"
            description = "YummyPC — unofficial YummyAnime desktop client"
            vendor = "YummyPC community port"
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
        }
    }
}
