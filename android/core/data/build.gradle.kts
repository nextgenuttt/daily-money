plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dailymoney.android.library)
    alias(libs.plugins.dailymoney.android.hilt)
}

android {
    namespace = "com.dailymoney.core.data"
    testOptions.unitTests.isIncludeAndroidResources = true
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
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
