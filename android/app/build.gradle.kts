import java.util.Properties

plugins {
    alias(libs.plugins.dailymoney.android.application)
    alias(libs.plugins.dailymoney.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// google-services.json comes from the Firebase console and is not committed. Without it the app
// builds and shows a "Firebase not configured" screen instead of crashing.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun setting(name: String, default: String = ""): String =
    localProperties.getProperty(name) ?: System.getenv(name) ?: default

android {
    namespace = "com.dailymoney.app"

    defaultConfig {
        applicationId = setting("APPLICATION_ID", "com.dailymoney.app")
        versionCode = setting("VERSION_CODE", "1").toInt()
        versionName = setting("VERSION_NAME", "0.1.0")

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${setting("GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("String", "AUTH_CONTINUE_URL", "\"${setting("AUTH_CONTINUE_URL")}\"")
        buildConfigField("String", "API_BASE_URL", "\"${setting("API_BASE_URL")}\"")
        buildConfigField("String", "API_CERT_PINS", "\"${setting("API_CERT_PINS")}\"")
        manifestPlaceholders["authActionHost"] = setting("AUTH_ACTION_HOST", "dailymoney-app.web.app")
        buildConfigField("String", "PRIVACY_URL", "\"https://${setting("AUTH_ACTION_HOST", "dailymoney-app.web.app")}/privacy\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Upload key for Play App Signing, kept out of git: RELEASE_STORE_FILE / _PASSWORD / RELEASE_KEY_ALIAS / _PASSWORD.
    val releaseStore = setting("RELEASE_STORE_FILE")
    if (releaseStore.isNotBlank()) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseStore)
            storePassword = setting("RELEASE_STORE_PASSWORD")
            keyAlias = setting("RELEASE_KEY_ALIAS")
            keyPassword = setting("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:home"))
    implementation(project(":feature:money"))
    implementation(project(":feature:reports"))
    implementation(project(":feature:reminders"))
    implementation(project(":feature:todo"))
    implementation(project(":feature:groups"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
}
