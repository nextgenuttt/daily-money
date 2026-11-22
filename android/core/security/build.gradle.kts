plugins {
    alias(libs.plugins.dailymoney.android.library)
    alias(libs.plugins.dailymoney.android.hilt)
}

android {
    namespace = "com.dailymoney.core.security"
}

dependencies {
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
