plugins {
    alias(libs.plugins.dailymoney.android.feature)
}

android {
    namespace = "com.dailymoney.feature.auth"
}

dependencies {
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
}
