// core — Java on Android, GNU LGPL v3 (see core/COPYING.LESSER). Based on Angel Aura Amethyst / PojavLauncher.
plugins {
    id("com.android.library")
}

android {
    namespace = "site.cubexcraft.core"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 26
        ndk {
            // only 64-bit ARM: the 1.21.1 pack does not fit a 32-bit JVM
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            ndkBuild {
                arguments += listOf("APP_SHORT_COMMANDS=true")
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.bytedance:bytehook:1.0.10")
    implementation("androidx.annotation:annotation:1.9.1")
}
