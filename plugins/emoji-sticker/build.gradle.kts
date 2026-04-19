import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kingzcheung.kime.plugin.emoji"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kingzcheung.kime.plugin.emoji"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
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
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    buildFeatures {
        compose = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

android.applicationVariants.all {
    val pluginName = "emoji-sticker"
    outputs.all {
        (this as BaseVariantOutputImpl).outputFileName = "$pluginName-$versionName.apk"
    }
}

dependencies {
    // Force annotations version to resolve conflict
    constraints {
        implementation("org.jetbrains:annotations:23.0.0")
    }
    
    // All dependencies from plugin-core (api) + host app
    compileOnly(project(":plugin-core"))
    
    // Plugin-specific dependency (must be implementation)
    implementation("io.coil-kt:coil-compose:2.5.0")
}