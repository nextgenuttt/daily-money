plugins {
    alias(libs.plugins.dailymoney.android.library)
    alias(libs.plugins.dailymoney.android.hilt)
}

android {
    namespace = "com.dailymoney.core.data"
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:security"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
}
