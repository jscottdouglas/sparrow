plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core-crypto"))
            implementation(libs.kotlinx.serialization.json)
        }
        // Desktop Sparrow wallet-file import needs Argon2id (BouncyCastle lightweight API)
        // plus JDK crypto/zip — shared between desktop JVM and Android like core-crypto.
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            }
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.sparrowwallet.mobile.storage"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
