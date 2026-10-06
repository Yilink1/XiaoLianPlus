plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.yilink1.xiaolianplus"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.yilink1.xiaolianplus"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.androidx.appcompat)
}