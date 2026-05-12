plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.environmentVariable("ANDROID_RELEASE_STORE_FILE")
val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile.isPresent &&
    releaseStorePassword.isPresent &&
    releaseKeyAlias.isPresent &&
    releaseKeyPassword.isPresent

android {
    namespace = "dev.harataku.healthconnectexporter"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.harataku.healthconnectexporter"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.2.4"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
}

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release") } && !hasReleaseSigning) {
        throw GradleException(
            "Release signing is required. Set ANDROID_RELEASE_STORE_FILE, " +
                "ANDROID_RELEASE_STORE_PASSWORD, ANDROID_RELEASE_KEY_ALIAS, and " +
                "ANDROID_RELEASE_KEY_PASSWORD."
        )
    }
}
