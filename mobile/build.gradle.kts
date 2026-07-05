plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// Sources live on /mnt/c (visible to Windows and WSL), but build output goes to the
// native filesystem — AGP's heavy I/O is unreliable and slow on the 9p /mnt/c mount.
val buildRoot = File(System.getProperty("user.home"), ".sparrow-ltc-mobile-build")
layout.buildDirectory.set(File(buildRoot, "root"))
subprojects {
    layout.buildDirectory.set(File(buildRoot, name))
}
