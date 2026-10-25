// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "site.cubexcraft.mobile"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        // the same id and key as CubeX Mobile 2.7.x: 3.0 installs over it and keeps the login and the game files
        applicationId = "site.cubexcraft.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 300000
        versionName = "3.0.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("cubex") {
            val keystore = System.getenv("CUBEX_KEYSTORE")?.let { file(it) }
            if (keystore != null && keystore.exists()) {
                storeFile = keystore
                storePassword = System.getenv("CUBEX_KEYSTORE_PASSWORD")
                keyAlias = "cubexcraft"
                keyPassword = System.getenv("CUBEX_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // test builds sit next to the released app on the phone
            applicationIdSuffix = ".v3dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("cubex").takeIf { it.storeFile != null }
        }
    }

    packaging {
        jniLibs {
            // the JVM and MobileGlues are loaded with dlopen by path: the libraries must be extracted to nativeLibraryDir
            useLegacyPackaging = true
            pickFirsts += listOf("**/libc++_shared.so", "**/libbytehook.so")
        }
    }

    androidResources {
        noCompress += listOf("xz", "jar")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // prebuilt natives from Angel Aura Amethyst 330c6eae (see THIRD_PARTY_NOTICES.md)
    implementation(files("libs/MobileGlues-release.aar", "libs/openal-soft-release.aar", "libs/lwjgl-3.3.3-natives-release.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.tukaani:xz:1.10")
}
