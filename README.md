# AntFfi — Kotlin/Android bindings for the Autonomi network

In-process Kotlin bindings for [Autonomi](https://autonomi.com), generated
from the [`ant-sdk`](https://github.com/WithAutonomi/ant-sdk) FFI crate
via [UniFFI](https://github.com/mozilla/uniffi-rs). Ships as a regular
Android Archive (`.aar`) with native libraries for the three Android ABIs
Google Play requires + ChromeOS. Talks directly to the network — no daemon
process required.

## Installation

The SDK is published to a Maven repository hosted on GitHub Pages
([`ant-maven`](https://github.com/WithAutonomi/ant-maven)). Add the repo, then
the dependency.

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://withautonomi.github.io/ant-maven/")
    }
}
```

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.autonomi:ant-android:0.0.7")
    // JNA (to call into libant_ffi.so) and kotlinx-coroutines-core are
    // pulled in transitively from the POM — no need to declare them.
}
```

That's it — the `.so` native libraries are inside the AAR; nothing to build
locally.

## Platforms

| ABI | Notes |
|---|---|
| `arm64-v8a`   | Modern Android devices — required by Google Play. |
| `armeabi-v7a` | Older 32-bit ARM devices — required by Google Play. |
| `x86_64`      | Android emulator on Intel hosts + ChromeOS. |

`x86` (32-bit Intel) is deprecated and not shipped.

Minimum `compileSdk`: 34. Minimum `minSdk`: 24 (Android 7.0).

## Choosing a connect method

Everything starts from a `Client`. Pick the constructor that matches how you
pay for uploads:

| Your goal | Constructor | You provide |
|---|---|---|
| **Download / read** public data only | `Client.connect(peers)` | bootstrap peers |
| **Upload**, the app holds the key | `Client.connectWithWallet(peers, privateKey, rpcUrl, paymentTokenAddress, paymentVaultAddress)` | key + EVM config |
| **Upload**, the *user's* wallet signs (WalletConnect) | `Client.connectForExternalSigner(peers, rpcUrl, paymentTokenAddress, paymentVaultAddress)` | EVM config (no key) — see below |
| **Local testing** against a devnet | `Client.connectLocal()` or `Client.connectFromDevnetManifest(path)` | a running devnet |

Every method is a `suspend fun` — call from a coroutine. A failure surfaces as
a typed `ClientException`.

## Usage

### Store and retrieve a chunk (local devnet)

```kotlin
import uniffi.ant_ffi.Client
import kotlinx.coroutines.runBlocking

runBlocking {
    val client = Client.connectLocal()

    val put = client.chunkPut("hello autonomi".toByteArray())
    val bytes = client.chunkGet(put.address)
    println("stored at ${put.address}")
}
```

### Upload with an app-held key

```kotlin
val client = Client.connectWithWallet(
    peers = listOf(/* network bootstrap multiaddrs */),
    privateKey = "0x…",
    rpcUrl = "https://…",
    paymentTokenAddress = "0x…",  // ANT token
    paymentVaultAddress = "0x…",  // payment vault
)

// Public: the data map is stored on the network; retrieve by address.
val pub = client.dataPutPublic(payload, "auto")
val back = client.dataGetPublic(pub.address)

// Private: you keep the returned hex data map; it's the only way back in.
val priv = client.dataPutPrivate(payload, "auto")
val secret = client.dataGetPrivate(priv.dataMap)
```

### Upload a file from disk (recommended for large data)

For anything larger than a small blob, upload **from a file path** rather than
loading bytes into memory. `ant-core` streams the file through self-encryption
and spills chunks to disk, so memory stays flat regardless of file size — the
in-memory `dataPut*` / `chunkPut` APIs hold the whole payload in RAM.

```kotlin
// Preview the cost before paying (sampled — fast, confidence-aware).
val est = client.estimateFileCost(path, "auto")
println("${est.chunkCount} chunks · ~${est.storageCostAtto} atto ANT · ${est.confidence}")

// Public: retrievable by address.
val put = client.fileUploadPublic(path, "auto")

// Private: keep the returned hex data map — it's the only way back in.
val priv = client.fileUploadPrivate(path, "auto")

// Download straight to disk (streams; ProgressListener is required).
val noop = object : ProgressListener { override fun onProgress(update: ProgressUpdate) {} }
val bytes = client.downloadPublicToFile(put.address, outPath, noop)
```

`CostEstimate` fields: `fileSize`, `chunkCount`, `storageCostAtto` (storage in
atto-ANT), `estimatedGasCostWei`, `paymentMode`, and `confidence` — a string
(`priced_sample`, `verified_all_already_stored`, …) telling you how firm the
estimate is, so you don't render a best-effort `0` as "free".

> **Memory model.** File uploads and `download*ToFile` **stream** (constant
> memory). The `dataPut*` / `dataGet*` / `chunk*` APIs load the full payload
> into a `ByteArray` — fine for small data, avoid for large. Prefer the
> file-path APIs on mobile.

### Error handling

```kotlin
try {
    client.dataGetPublic(addr)
} catch (e: ClientException.NotFound) {
    // address isn't on the network
} catch (e: ClientException.NetworkError) {
    // transient — safe to retry
} catch (e: ClientException.PaymentError) {
    // …
}
```

### Paying with the user's own wallet (external signer)

`connectForExternalSigner` never holds a key. The flow is: `prepareFileUpload`
→ `paymentTransactions(uploadId)` (the SDK returns the ready-to-sign `approve` +
`pay` transactions — you never build calldata) → sign each with the user's
wallet and `waitForReceipt` → `finalizeUpload` (wave) or `finalizeUploadMerkle`
(merkle). All ABI encoding, receipt polling, and the merkle-winner lookup live
in the SDK. Note `waitForReceipt` / `merkleWinnerPoolHash` / `networkInfo` are
free functions (`import uniffi.ant_ffi.*`); only `paymentTransactions` /
`prepare*` / `finalize*` are `Client` methods.

The full step-by-step flow, with Kotlin **and** Swift worked examples, wave vs
merkle routing, and the paid-but-not-stored retry contract, is documented in
[`ant-sdk/docs/mobile-external-signer.md`](https://github.com/WithAutonomi/ant-sdk/blob/main/docs/mobile-external-signer.md).
See [`ant-mobile-android`](https://github.com/WithAutonomi/ant-mobile-android)
for a complete working reference (WalletConnect wiring, both payment paths, live
progress).

> The older [`external-signer-flow.md`](https://github.com/WithAutonomi/ant-sdk/blob/main/docs/external-signer-flow.md)
> describes the **antd daemon REST** flow (HTTP endpoints, hand-rolled ABI) — it
> does **not** apply to this mobile SDK. Use the mobile doc above.

> Note: the SDK models uniffi `suspend fn`s with kotlinx-coroutines. For
> `Dispatchers.Main` in a UI app you may also want
> `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:…")`, which
> is not transitive from the SDK.

## Parameter reference

- **`paymentMode`** (`dataPutPublic` / `dataPutPrivate` / `fileUploadPublic`):
  `"auto"` (default — picks the cheapest batching for the size), `"single"`,
  or `"merkle"`.
- **`visibility`** (`prepareDataUpload` / `prepareFileUpload`): `"public"`
  (retrieve by address) or `"private"` (retrieve with the hex data map you keep).
- **Addresses & data maps** are hex strings. Chunk/data payloads cross the FFI
  as `ByteArray`.

## Versioning

Versions are built from [`ant-sdk`](https://github.com/WithAutonomi/ant-sdk)'s
`ffi/` source and published to
[`ant-maven`](https://github.com/WithAutonomi/ant-maven) (the Maven repository
the install snippet above uses) via ant-maven's manual `publish-android`
workflow. This repo hosts the source and generated bindings; **GitHub releases
are not cut here** — published AARs and available versions live in ant-maven's
[`maven-metadata.xml`](https://withautonomi.github.io/ant-maven/com/autonomi/ant-android/maven-metadata.xml).
The iOS SDK ([`ant-swift`](https://github.com/WithAutonomi/ant-swift)) is
released at the same version.

## License

Dual-licensed under either MIT or Apache 2.0, at your option.
