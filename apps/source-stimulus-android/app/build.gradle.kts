plugins {
    id("com.android.application")
}

android {
    namespace = "com.hapticasensorics.phonecapturesourcestimulus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hapticasensorics.phonecapturesourcestimulus"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core:1.17.0")
}

