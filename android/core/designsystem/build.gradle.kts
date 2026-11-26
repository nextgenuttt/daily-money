plugins {
    alias(libs.plugins.dailymoney.android.compose)
}

android {
    namespace = "com.dailymoney.core.designsystem"
}

dependencies {
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(project(":core:domain"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
