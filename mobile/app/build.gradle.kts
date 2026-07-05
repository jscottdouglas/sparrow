plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.sparrowwallet.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sparrowwallet.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 46
        versionName = "0.16.2"
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-crypto"))
    implementation(project(":electrum"))
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(libs.kotlinx.serialization.json)
    // mwebd scanner: gomobile-built daemon + the same gRPC client the desktop uses
    implementation(files("libs/mwebd.aar"))
    implementation("io.grpc:grpc-okhttp:1.79.0")
    implementation("io.grpc:grpc-protobuf:1.79.0")
    implementation("io.grpc:grpc-stub:1.79.0")
    implementation("com.google.protobuf:protobuf-java:4.34.0")
}
