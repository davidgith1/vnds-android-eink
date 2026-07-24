plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.vndsandroideink"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.vndsandroideink"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.documentfile)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.5.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}