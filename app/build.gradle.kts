plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.resonanse.hlwatchface"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.resonanse.hlwatchface"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.wear.watchface:watchface:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-rendering:1.2.1")
    implementation("androidx.wear.watchface:watchface-style:1.2.1")
    implementation("androidx.wear.watchface:watchface-editor:1.2.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.health:health-services-client:1.0.0-beta03")
    // health-services exposes ListenableFuture in its API surface, but only pulls
    // Guava in at runtime scope — declare it so it is on the compile classpath too.
    implementation("com.google.guava:guava:31.1-android")
    compileOnly("com.google.android.wearable:wearable:2.9.0")
}
