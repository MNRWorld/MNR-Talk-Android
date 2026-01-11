plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.io.File
import java.util.Properties

android {
    namespace = "world.mnr.talk"
    compileSdk = 36

    defaultConfig {
        applicationId = "world.mnr.talk"
        minSdk = 21
        targetSdk = 36
        versionCode = 110
        versionName = "1.1.0"

        vectorDrawables.useSupportLibrary = true
    }

    // Read keystore properties from local.properties (not in version control)
    val keystorePropertiesFile = rootProject.file("local.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(keystorePropertiesFile.inputStream())
    }

    // Keystore is in project root
    val keystoreFile = rootProject.file(keystoreProperties.getProperty("keystore.file") ?: "release.keystore")
    val hasKeystore = keystoreFile.exists()

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = keystoreFile
                storePassword = keystoreProperties.getProperty("keystore.password")
                keyAlias = keystoreProperties.getProperty("keystore.key.alias")
                keyPassword = keystoreProperties.getProperty("keystore.key.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
            isDebuggable = false
            isJniDebuggable = false
            renderscriptOptimLevel = 3
        }
        
        create("releaseUnsigned") {
            initWith(getByName("release"))
            signingConfig = null
            applicationIdSuffix = null
        }
        
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
        viewBinding = false
        dataBinding = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.11.0")
}
