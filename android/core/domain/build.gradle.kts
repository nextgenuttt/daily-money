plugins {
    alias(libs.plugins.dailymoney.jvm.library)
}

dependencies {
    api(libs.javax.inject)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.serialization.json)
}
