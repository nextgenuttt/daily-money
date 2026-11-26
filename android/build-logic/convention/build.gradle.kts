plugins {
    `kotlin-dsl`
}

group = "com.dailymoney.buildlogic"

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "dailymoney.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "dailymoney.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "dailymoney.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "dailymoney.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "dailymoney.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("kmpLibrary") {
            id = "dailymoney.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "dailymoney.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
