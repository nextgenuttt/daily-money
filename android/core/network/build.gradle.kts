plugins {
    alias(libs.plugins.dailymoney.android.library)
    alias(libs.plugins.dailymoney.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dailymoney.core.network"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    api(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
}
