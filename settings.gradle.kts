import java.util.Properties

pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    val localProps = Properties().apply {
        val f = file("local.properties")
        if (f.exists()) f.inputStream().use(::load)
    }
    val ghUser = localProps.getProperty("gpr.user")
        ?: providers.gradleProperty("gpr.user").orNull
        ?: System.getenv("GITHUB_ACTOR")
    val ghToken = localProps.getProperty("gpr.key")
        ?: providers.gradleProperty("gpr.key").orNull
        ?: System.getenv("GITHUB_TOKEN")
        ?: runCatching {
            providers.exec {
                commandLine("gh", "auth", "token")
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // libadb-android + spake2 (built-in wireless ADB) and MorpheApp ARSCLib/smali ship on JitPack
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\.MuntashirAkon.*")
                includeGroup("com.github.MorpheApp")
                includeGroup("com.github.MorpheApp.smali")
            }
        }
        // Morphe Patcher runtime library (app.morphe:morphe-patcher)
        maven("https://maven.pkg.github.com/MorpheApp/registry") {
            credentials {
                username = ghUser.orEmpty().ifBlank { "anonymous" }
                password = ghToken.orEmpty()
            }
        }
    }
}
rootProject.name = "Orion-RE"
include(":app")
