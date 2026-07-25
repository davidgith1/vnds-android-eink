import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing is optional: `keystore.properties` is gitignored and only present on machines
// set up to cut a signed release. Its absence (e.g. a fresh clone) must not break `assembleDebug`
// or any other task -- release builds just come out unsigned in that case.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.davidgith1.vndsandroideink"
    compileSdk {
        version = release(37)
    }

    // AGP embeds a "Dependency metadata" block in the signed APK by default (for Play Console).
    // F-Droid's scanner flags any extra signing block as suspicious, so it's disabled here.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "io.github.davidgith1.vndsandroideink"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // "full" bundles Onyx's proprietary onyxsdk-device (Boox hardware fast-refresh support) --
    // see EinkRefreshManager's flavor-specific implementations under src/full and src/free. "free"
    // has no proprietary dependencies at all, for distribution through F-Droid; it's functionally
    // identical except EinkRefreshManager.isSupported() is always false there, so einkMode falls
    // back to its ordinary (non-Onyx) instant-update behavior on every device, including Boox ones.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("free") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
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
    "fullImplementation"("com.onyx.android.sdk:onyxsdk-device:1.3.5.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}