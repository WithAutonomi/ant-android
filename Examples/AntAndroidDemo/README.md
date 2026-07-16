# AntAndroidDemo

A **minimal** Android Compose app that exercises the Autonomi SDK end-to-end:
type a message, tap Upload, get a chunk address back, paste it into Download to
round-trip the content. This is a bare `chunkPut` / `chunkGet` smoke test — for
the full file-based paid-upload app (cost preview, WalletConnect external-signer
payments, wave/merkle), see
[`ant-mobile-android`](https://github.com/WithAutonomi/ant-mobile-android).

Mirrors the [SwiftUI demo](https://github.com/WithAutonomi/ant-swift/tree/main/Examples/AntSwiftDemo)
in the `ant-swift` repo.

## SDK dependency

Consumes the published SDK from the [ant-maven](https://github.com/WithAutonomi/ant-maven)
GitHub Pages Maven repo — `implementation("com.autonomi:ant-android:0.0.7")`
(repo declared in `settings.gradle.kts`; JNA + coroutines-core come transitively).
Bump the version to adopt a newer release; no local AAR to manage.

## Prerequisites

- **Android Studio** *or* a command-line Android toolchain:
  - `brew install --cask android-commandlinetools`
  - JDK 17: `brew install openjdk@17` (`export JAVA_HOME=/opt/homebrew/opt/openjdk@17`)
  - Android SDK platform 34 + build-tools 34.0.0 (+ an arm64-v8a emulator image
    if not using a physical device). Set `ANDROID_HOME` (e.g. `~/Library/Android/sdk`).
  - Build with the bundled wrapper (`./gradlew`, Gradle 8.9).
- A physical device or emulator, and a devnet to connect to (below).

## Starting a devnet

The demo uses `Client.connectFromDevnetManifest(MANIFEST_PATH)` against a devnet.
Start a local one from an [`ant-client`](https://github.com/WithAutonomi/ant-client)
checkout:

```sh
# One-time: move cached public bootstrap peers aside, or the devnet's bootstrap
# nodes hang dialing unreachable hosts. Restore afterwards.
mv ~/Library/Caches/saorsa/bootstrap/bootstrap_cache.json \
   ~/Library/Caches/saorsa/bootstrap/bootstrap_cache.json.aside
cargo run --release --example start-local-devnet --features devnet
```

It writes the manifest at `~/Library/Application Support/ant/devnet-manifest.json`.

## Wiring the manifest onto the device

This minimal demo reads the manifest from a fixed on-device path
(`MANIFEST_PATH`), so push it in with `adb`:

```sh
adb push "$HOME/Library/Application Support/ant/devnet-manifest.json" \
   /data/local/tmp/devnet-manifest.json
```

- **Physical device on the same LAN (recommended):** run a devnet that
  advertises the host's LAN IP with the `ant-devnet` harness in
  [`ant-node`](https://github.com/WithAutonomi/ant-node) (≥ v0.14.4; run from a
  source checkout — it is not in the release tarballs):

  ```sh
  cargo run --release --bin ant-devnet -- --preset small --enable-evm \
    --host <lan-ip> \
    --manifest "$HOME/Library/Application Support/ant/devnet-manifest.json"
  ```

  then push that manifest (adb command above). Cross-device LAN devnet works
  with the released SDK (≥ 0.0.7): the SDK's `connectFromDevnetManifest*`
  auto-detects a LAN vs loopback manifest and binds `0.0.0.0` when a
  non-loopback bootstrap addr is present.
- **Emulator:** `127.0.0.1` inside the emulator is the emulator, not the host,
  and its NAT does not reliably reach loopback-bound devnet services — prefer a
  physical device on the LAN.

## Build & run

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk
cd Examples/AntAndroidDemo
./gradlew :app:installDebug
adb shell am start -n com.autonomi.examples.antdemo/.MainActivity
```

## What it does

- **Upload** — appends a random suffix to the input text (content-addressed
  storage: identical content always lands at the same address), stores it with
  `chunkPut`, and shows the resulting address.
- **Download** — paste any chunk address (or "Use last") and pull the content
  back with `chunkGet`.

## Caveats

- This is a **devnet** smoke demo; the devnet-manifest connect path is test-only.
- The app plants `HOME` via a libc `setenv` shim before the first FFI call
  (`AntFfiBootstrap.kt`) because `ant-core` reads `$HOME` and otherwise aborts
  with `HomeDirNotFound`. Workaround pending an explicit data-dir FFI arg.
- The bundled signing config is `debug` for one-tap install/run. Don't ship to
  Play with this.
