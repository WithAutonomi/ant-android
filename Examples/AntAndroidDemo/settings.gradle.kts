pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // Reown AppKit (WalletConnect spike) pulls several transitive deps that
        // are only published on JitPack: com.walletconnect.Scarlet, the
        // komputing/kethereum crypto libs, custom-qr-generator, multiformats.
        maven("https://jitpack.io")
    }
}
rootProject.name = "AntAndroidDemo"
include(":app")
