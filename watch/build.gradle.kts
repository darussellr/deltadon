plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The Samsung Health Sensor SDK AAR is not redistributable; drop it into
// watch/libs/ (see watch/libs/README.md) and the real sensing implementation
// in src/samsung/ compiles in automatically. Without it the app builds and
// runs against the synthetic repository.
val samsungAar = file("libs/samsung-health-sensor-api.aar")

android {
    namespace = "com.smartwake.watch"
    compileSdk = 35

    defaultConfig {
        // Same applicationId as the phone module (different devices) so the
        // Wearable Data Layer pairs the two apps.
        applicationId = "com.smartwake"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    sourceSets {
        getByName("main") {
            if (samsungAar.exists()) {
                java.srcDir("src/samsung/java")
            }
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.play.services)

    if (samsungAar.exists()) {
        implementation(files(samsungAar))
    }
}
