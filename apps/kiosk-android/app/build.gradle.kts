plugins {
    id("com.android.application")
}

android {
    namespace = "com.hapticasensorics.phonecapturekiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hapticasensorics.phonecapturekiosk"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
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
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7") {
        exclude(group = "com.gyf.immersionbar", module = "immersionbar")
        exclude(group = "com.zlc.glide", module = "webpdecoder")
    }
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7")
}
