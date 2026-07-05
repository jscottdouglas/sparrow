# Sparrow-LTC Mobile — Architecture Design

Status: draft v1 (2026-07-01)
Decision: **full hot wallet**, **Android + iOS together** via **Kotlin Multiplatform + Compose Multiplatform**.

## Why KMP (not Flutter, not Gluon/JavaFX-mobile)

- The desktop wallet core (`drongo`) is Java. Java→Kotlin porting is largely mechanical;
  Java→Dart (Flutter) would be a from-scratch reimplementation of the crypto core.
- The MWEB layer is Go (`sparrow/mweb` wrapping `github.com/ltcmweb/mwebd`) and is already
  mobile-proven — Cake Wallet ships the same `mwebd` on iOS/Android via `gomobile`.
- Gluon (JavaFX on mobile) is rejected: desktop UI is wrong for phones, and the
  GraalVM + JNA + Go-lib + JavaFX toolchain is brittle.

## Module layout

```
:core-crypto   (common Kotlin)  — drongo port: BIP39/32, LTC addresses, ScriptType, PSBT, tx serialization
:mweb          (expect/actual)  — Go mwebd bound per platform behind one MwebService interface
:electrum      (common Kotlin)  — Electrum protocol client over Ktor raw TCP/TLS (+ optional Tor proxy)
:wallet        (common Kotlin)  — wallet model, coin selection, fees, MWEB peg-in/peg-out flows
:storage       (expect/actual)  — encrypted wallet+seed at rest (Android Keystore / iOS Keychain, biometrics)
:app           (Compose MP)     — shared UI for Android + iOS
```

## Key dependency choices

| Problem | Solution |
|---|---|
| secp256k1 (both platforms) | `secp256k1-kmp` (ACINQ) — ECDSA + privkeyTweakAdd for BIP32 |
| BigInteger in common code | `com.ionspin.kotlin:bignum` |
| sha256/ripemd160/hmac/pbkdf2 | `KotlinCrypto/hash` + `secure-random` |
| MWEB scan/sign | Keep in Go: `gomobile bind` → `.aar` (Android), `.xcframework` (iOS). Extend `sparrow/mweb/build.sh` with android/arm64 + ios/arm64 targets |
| Electrum networking | `io.ktor:ktor-network` raw sockets |
| Seed at rest | Android Keystore / iOS Keychain via expect/actual, biometric unlock, FLAG_SECURE |

## Parity with desktop (non-negotiable)

Mobile and desktop MUST derive byte-identical keys/addresses from the same seed and read
the same MWEB view-key export format (scan secret 32B + spend pubkey 33B, per
`Keystore.getMwebScanPrivateKey()` at `<account>/0'` and `getMwebSpendPublicKey()` at `<account>/1'`).

Ground truth: `mobile/vectors/golden-vectors.json`, generated from drongo by
`sparrow/drongo/src/test/java/com/sparrowwallet/drongo/wallet/GoldenVectorGeneratorTest.java`
(`./gradlew :drongo:test --tests '*GoldenVectorGenerator*'`). The Kotlin `:core-crypto`
test suite must pass every vector before any feature work builds on it. Vectors cover:
master fingerprint, xprv, account xpubs, receive/change addresses, pubkeys, WIFs, and
MWEB scan/spend keys across P2PKH / P2SH-P2WPKH / P2WPKH / MWEB (`m/1000'`) for three seeds
(standard BIP39 vector, drongo test seed, ditto with passphrase).

LTC specifics the vectors pin down: bech32 HRP `ltc`, base58 P2PKH version (L-addresses),
WIF version, MWEB address encoding, coin type `2'` derivation paths.

## Signing paths (two, by output type)

- Regular LTC (P2PKH/P2SH-P2WPKH/P2WPKH): sign in Kotlin via secp256k1-kmp.
- MWEB outputs + peg-in/peg-out: sign inside Go `mwebd` (as desktop does).
- Peg semantics follow the desktop Public/Private flow: peg-in change is public,
  peg-out change is private.

## Build phases

1. `:core-crypto` port + golden-vector parity tests ← foundation, in progress
2. `:storage` + keychain + seed create/import + receive addresses
3. `:electrum` → regular LTC balance & history
4. `:mweb` binding → MWEB balance/scan (reuses view-key path)
5. Send + sign regular LTC
6. MWEB send + peg-in/peg-out (port desktop peg-button flows)
7. Compose MP UI polish + security hardening + external audit

## Risks

- Hot-wallet key security is the highest-stakes surface — external audit before release.
- App-store crypto-wallet policies (Apple/Google non-custodial rules, disclosures).
- Background MWEB scanning vs. Android Doze / iOS background limits — study Cake Wallet.
- gomobile binary size and Go runtime overhead on iOS.

## Testing & distribution roadmap (first mobile app)

1. **Now (no new tools):** all wallet logic tests run on the JVM target in WSL — this covers
   months of core work. Compose Multiplatform's desktop target can preview UI on Windows too.
2. **Android device testing ($0):** install Android Studio on Windows (repo at
   C:\projects\sparrow-ltc\mobile is visible from both Windows and WSL). Test on the built-in
   emulator, or a real phone via USB debugging (Settings → tap Build Number 7× → enable USB
   debugging). Sideloading your own APK needs no account.
3. **Google Play ($25 one-time):** Play Console account; personal accounts must run a closed
   test (~12 testers, 14 days — check current policy) before production. Non-custodial wallets
   are allowed with the financial-features declaration. GitHub APK releases are a store-free
   alternative wallet users widely accept.
4. **iOS (later, bigger lift):** building/signing requires a Mac (used M-series Mac mini or a
   cloud Mac; GitHub Actions macOS runners can do CI builds). Apple Developer Program is
   $99/yr, and guideline 3.1.5 requires cryptocurrency wallets to be published by an
   **organization** account — i.e. a legal entity with a D-U-N-S number. Plan the entity
   before App Store submission; TestFlight testing also needs the paid account.

## Repo

Mobile code lives in `mobile/` (this directory), separate from the `sparrow/` desktop clone.
Nothing here is committed to the desktop repos. Vectors are regenerated, never hand-edited.
