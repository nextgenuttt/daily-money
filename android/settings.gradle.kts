pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DailyMoney"

include(":app")
include(":core:domain")
include(":core:designsystem")
include(":core:security")
include(":core:database")
include(":core:network")
include(":core:data")
include(":feature:auth")
include(":feature:home")
include(":feature:money")
include(":feature:reports")
include(":feature:reminders")
include(":feature:todo")
