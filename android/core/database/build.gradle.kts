plugins {
    alias(libs.plugins.dailymoney.android.library)
    alias(libs.plugins.dailymoney.android.hilt)
}

android {
    namespace = "com.dailymoney.core.database"
    defaultConfig {
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
}

dependencies {
    implementation(project(":core:security"))
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
}
