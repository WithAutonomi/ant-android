# AntFfi — Kotlin/Android bindings for the Autonomi network

In-process Kotlin bindings for [Autonomi](https://autonomi.com), generated
from the [`ant-sdk`](https://github.com/WithAutonomi/ant-sdk) FFI crate
via [UniFFI](https://github.com/mozilla/uniffi-rs). Ships as a regular
Android Archive (`.aar`) with native libraries for the three Android ABIs
Google Play requires + ChromeOS.

## Installation

Releases are published as a `.aar` attached to each [GitHub
Release](https://github.com/WithAutonomi/ant-android/releases). A proper
Maven Central listing is planned (tracked in `ant-sdk#149`); for v0.0.1
the file-based dependency below is the recommended path.

1. Download `ant-android-release.aar` from
   [the latest release](https://github.com/WithAutonomi/ant-android/releases/latest).
2. Drop it into your app module's `libs/` directory.
3. Wire it up in your app's `build.gradle.kts`:

   ```kotlin
   dependencies {
       implementation(files("libs/ant-android-release.aar"))

       // Transitive deps — the AAR uses JNA to call into libant_ffi.so,
       // and kotlinx-coroutines to model uniffi's `suspend fn` wrappers.
       implementation("net.java.dev.jna:jna:5.14.0@aar")
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
   }
   ```

   And in your `settings.gradle.kts` make sure both `google()` and
   `mavenCentral()` are configured as repositories.

## Platforms

| ABI | Notes |
|---|---|
| `arm64-v8a`   | Modern Android devices — required by Google Play. |
| `armeabi-v7a` | Older 32-bit ARM devices — required by Google Play. |
| `x86_64`      | Android emulator on Intel hosts + ChromeOS. |

`x86` (32-bit Intel) is deprecated and not shipped.

Minimum `compileSdk`: 34. Minimum `minSdk`: 24 (Android 7.0).

## Usage

```kotlin
import uniffi.ant_ffi.Client
import kotlinx.coroutines.runBlocking

runBlocking {
    val client = Client.connectLocal()
    val data = "hello autonomi".toByteArray()
    val result = client.chunkPut(data)
    println("Stored at ${result.address}")
}
```

## Versioning

Releases are cut in lockstep with [`ant-sdk`](https://github.com/WithAutonomi/ant-sdk):
a tag `vX.Y.Z` in `ant-sdk` triggers a matching `vX.Y.Z` release here.

## License

Dual-licensed under either MIT or Apache 2.0, at your option.
