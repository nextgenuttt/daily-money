plugins {
    alias(libs.plugins.dailymoney.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            // Android wires use cases with Hilt; iOS will construct them directly.
            api(libs.javax.inject)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
