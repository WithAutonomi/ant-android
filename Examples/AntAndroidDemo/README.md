# AntAndroidDemo

A minimal Android Compose app that exercises the AntFfi AAR end-to-end:
type a message, tap Upload, get a chunk address back, paste it into
Download to round-trip the content.

Mirrors the [SwiftUI demo](https://github.com/WithAutonomi/ant-swift/tree/main/Examples/AntSwiftDemo)
shipped in the `ant-swift` repo.

## Prerequisites

- **Android Studio** *or* a working command-line Android toolchain:
  - `brew install --cask android-commandlinetools`
  - JDK 17: `brew install openjdk@17` (set `JAVA_HOME=/opt/homebrew/opt/openjdk@17`)
  - Gradle: `brew install gradle`
  - Android SDK platform 34 + build-tools 34.0.0 + the arm64-v8a emulator
    system image (`sdkmanager --install "platforms;android-34" "build-tools;34.0.0" "system-images;android-34;google_apis;arm64-v8a" "emulator"`)
- Rust toolchain (only if you want to rebuild the AAR yourself —
  v0.0.1 is pre-bundled in `app/libs/`).
- `anvil` (Foundry): `brew install foundry` — for the local devnet.
- A booted Android emulator (or a real device on the same WiFi as
  your dev machine — see Real-device notes below).

### Bundled AAR

`app/libs/ant-android-release.aar` is the prebuilt v0.0.1 artifact.
To swap to a newer release, drop it in over the existing file.

## Starting the local devnet

The demo uses `Client.connectFromDevnetManifest` against a devnet
running on the host. From a checkout of
[`ant-client`](https://github.com/WithAutonomi/ant-client):

```sh
# One-time: move any cached public bootstrap peers aside, otherwise the
# devnet's bootstrap nodes hang trying to dial unreachable hosts.
mv ~/Library/Caches/saorsa/bootstrap/bootstrap_cache.json \
   ~/Library/Caches/saorsa/bootstrap/bootstrap_cache.json.aside

cargo run --release --example start-local-devnet --features devnet
```

It writes the manifest at `~/Library/Application Support/ant/devnet-manifest.json`.

## Wiring the manifest into the emulator

The Android emulator doesn't share the host filesystem like iOS
Simulator does — and `127.0.0.1` inside the emulator is the emulator
itself, not the host. Rewrite peer/RPC addresses to `10.0.2.2` (the
emulator's NAT alias for host) and push the manifest in:

```sh
sed 's|127\.0\.0\.1|10.0.2.2|g; s|localhost|10.0.2.2|g' \
    "$HOME/Library/Application Support/ant/devnet-manifest.json" \
    > /tmp/devnet-manifest-android.json
adb push /tmp/devnet-manifest-android.json /data/local/tmp/devnet-manifest.json
```

> **Known limitation**: the ant-node devnet currently binds its
> bootstrap peers + Anvil RPC to `127.0.0.1` only. Even with the IP
> rewrite, the emulator's NAT may not establish QUIC connections to
> loopback-bound services. Real-device + same-WiFi works because the
> devnet's IP is a normal LAN address; emulator + devnet is a known
> gap tracked separately. The `Client.connectLocal()` and live-network
> flows are not affected.

## Build and run

```sh
cd Examples/AntAndroidDemo
./gradlew :app:installDebug
# launch via the emulator's app drawer, or:
adb shell am start -n com.autonomi.examples.antdemo/.MainActivity
```

## What it does

- **Upload**: appends a random suffix to the input text so successive
  taps produce distinct chunks (content-addressed storage means
  identical content always lands at the same address). Uploads as a
  chunk, displays the resulting address.
- **Download**: paste any chunk address (or tap "Use last") and pull
  the content back as text.

## Real-device notes

A real Android device on the same LAN as your dev machine bypasses
the emulator NAT issue. Two extra steps:
1. Bind the devnet's services to `0.0.0.0` (currently requires an
   upstream change in `ant-node`).
2. Replace `10.0.2.2` in the rewrite step with your Mac's LAN IP.

## Production caveats

- This demo plants `HOME` via a libc `setenv` shim before calling the
  FFI (see `AntFfiBootstrap.kt`). `ant-core` reads `$HOME` and aborts
  with `HomeDirNotFound` otherwise. The proper fix is for the FFI to
  accept an explicit data-dir argument; this shim is a workaround
  pending that.
- The bundled signing config is `debug` for one-tap install/run. Don't
  ship to Play with this.
