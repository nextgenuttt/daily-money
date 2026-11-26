import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Shared Kotlin for Android and (later) iOS. Code in commonMain must not use java.* APIs; JVM-only code
 * lives in jvmMain until it is ported. iOS targets are added once Xcode is installed on the build Mac.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(17)
            jvm()
            iosArm64()
            iosSimulatorArm64()
            sourceSets.getByName("commonMain").dependencies {
                implementation(libs.lib("kotlinx-coroutines-core"))
            }
            sourceSets.getByName("jvmTest").dependencies {
                implementation(libs.lib("junit"))
                implementation(libs.lib("kotlinx-coroutines-test"))
                implementation(libs.lib("turbine"))
            }
        }
    }
}
