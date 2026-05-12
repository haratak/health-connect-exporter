plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.harataku.healthconnectexporter"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.harataku.healthconnectexporter"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
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
