import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

layout.buildDirectory.set(file("/tmp/instafolio_desktop_build"))

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    // HTTP client
    implementation(libs.okhttp)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    // JSON
    implementation(libs.gson)
    implementation("org.json:json:20240303")

    // PDF generation for desktop
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.prakash.instafolio.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = "InstaFolio"
            packageVersion = "2.6.0"
            description = "Universal Instagram media downloader & carousel binder for macOS"
            copyright = "© 2026 Prakash. All rights reserved."
            vendor = "Prakash"

            macOS {
                bundleID = "com.prakash.instafolio.desktop"
                dockName = "InstaFolio"
                minimumSystemVersion = "12.0"
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
        }
    }
}
