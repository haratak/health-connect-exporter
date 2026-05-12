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
        versionCode = 1
        versionName = "0.1.0"
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
}
