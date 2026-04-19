import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val onnxVersion = "1.20.0"
val onnxAarUrl = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${onnxVersion}/onnxruntime-android-${onnxVersion}.aar"

val downloadOnnx by tasks.registering {
    val cppDir = file("src/main/cpp/onnxruntime")
    val jniLibsDir = file("src/main/jniLibs")
    
    outputs.dir(cppDir)
    outputs.dir(jniLibsDir)
    
    doLast {
        val tmpDir = temporaryDir
        val aarFile = File(tmpDir, "onnxruntime.aar")
        
        // Check if already downloaded
        val libDir = file("src/main/cpp/onnxruntime/lib")
        val universalSo = file("src/main/jniLibs/arm64-v8a/libonnxruntime.so")
        if (universalSo.exists()) {
            println("ONNX Runtime files already exist, skipping download")
            return@doLast
        }
        
        println("Downloading ONNX Runtime ${onnxVersion}...")
        
        ant.invokeMethod("get", mapOf("src" to onnxAarUrl, "dest" to aarFile))
        
        copy {
            from(zipTree(aarFile))
            into(tmpDir)
        }
        
        copy {
            from(File(tmpDir, "headers"))
            into(File(cppDir, "include"))
        }
        
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        abis.forEach { abi ->
            copy {
                from(File(File(tmpDir, "jni"), abi))
                include("libonnxruntime.so")
                into(File(File(cppDir, "lib"), abi))
            }
            copy {
                from(File(File(tmpDir, "jni"), abi))
                include("libonnxruntime.so")
                into(File(jniLibsDir, abi))
            }
        }
        
        println("ONNX Runtime downloaded successfully")
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadOnnx)
}

android {
    namespace = "com.kingzcheung.kime.plugin.prediction"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kingzcheung.kime.plugin.prediction"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
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
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

android.applicationVariants.all {
    val pluginName = "prediction-onnx"
    outputs.all {
        val abi = filters.find { it.filterType.toString() == "ABI" }?.identifier ?: "universal"
        (this as BaseVariantOutputImpl).outputFileName = "$pluginName-$versionName-$abi.apk"
    }
}

dependencies {
    // Force annotations version to resolve conflict
    constraints {
        implementation("org.jetbrains:annotations:23.0.0")
    }
    
    // All dependencies from plugin-core (api) + host app
    compileOnly(project(":plugin-core"))
}