plugins {
    alias(libs.plugins.kotlin.jvm)
}

layout.buildDirectory.set(file("/tmp/instafolio_shared_build"))

dependencies {
    // HTTP client
    implementation(libs.okhttp)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // JSON
    implementation(libs.gson)
    implementation("org.json:json:20240303")

    testImplementation(kotlin("test"))
}
