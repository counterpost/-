plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.counterpost.workouttimer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.counterpost.workouttimer"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Same key on every build, so a new APK installs over the old one.
    signingConfigs {
        create("shared") {
            storeFile = file("workout-timer.keystore")
            storePassword = "workouttimer"
            keyAlias = "workouttimer"
            keyPassword = "workouttimer"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webassets"))
}

// The UI is the same app.html as the web version.
val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../app.html"))
    into(layout.buildDirectory.dir("generated/webassets"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }
