pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "sparrow-ltc-mobile"

include(":core-crypto")
include(":electrum")
include(":storage")
include(":app")
// Planned modules (see DESIGN.md):
// include(":mweb")      — gomobile-bound mwebd behind MwebService
// include(":wallet")    — wallet model, coin selection, peg-in/peg-out flows
// include(":storage")   — encrypted seed/wallet at rest (Keystore/Keychain)
// include(":app")       — Compose Multiplatform UI
