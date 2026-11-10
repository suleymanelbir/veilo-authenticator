# Veilo Authenticator

> The product name is defined in one place (`app_name` in
> `app/src/main/res/values/strings.xml`). The application ID is `tr.veilo.authenticator`;
> the code keeps the `com.selbir.authenticator` namespace.
>
> Part of the [Veilo](https://veilo.tr) ecosystem: secure communication, device
> management and now two-factor authentication — all under one roof.

An offline, open-source one-time-password authenticator for Android whose keys live in
the device's secure hardware.

**Status: Phase 1 complete — in Google Play closed testing.** All features have been
exercised on a physical device (Samsung). The OTP core is tested against the RFC vectors.

## What it does today

- TOTP (RFC 6238) and HOTP (RFC 4226), SHA-1 / SHA-256 / SHA-512, 6–8 digits.
- Add accounts by scanning an `otpauth://` QR code or typing the secret.
- Two ways to store each account's secret, chosen per account:
  - **Bound to this device** — the secret is imported into the Android Keystore as a
    non-exportable HMAC key. Codes are computed inside secure hardware; the secret never
    returns to app memory and cannot be backed up or cloned. Where the device has a
    StrongBox security chip that can do the account's HMAC, the key goes there, otherwise
    into the TEE. Each account shows which one the Keystore reports.
  - **Can be backed up** — the secret is encrypted with an AES-256-GCM key held in the
    Keystore and is in app memory only while a code is computed.
- Encrypted backup: backup-capable accounts can be exported to a passphrase-protected
  file (Argon2id + AES-256-GCM) through the system file picker and imported on another
  device. Accounts already present are skipped on import, including ones bound to the
  device. The file format is documented in `core/BackupCodec` and `core/BackupPayload`.
- Every key requires a strong biometric or the device credential within the last two
  minutes. The Keystore enforces this, not app logic.
- A copied code is flagged sensitive and removed from the clipboard after 30 seconds.
- Locks when it leaves the foreground; screenshots, screen recording and the recents
  thumbnail are blocked (`FLAG_SECURE`).
- Warns (without blocking) when the device looks rooted or modified, or when a debugger
  is attached to the app.
- No network access in the public edition: the `INTERNET` permission is stripped from the
  merged manifest. Android cloud backup and device-to-device transfer are disabled. (The
  separate Enterprise flavour talks to Veilo Gateway; see [docs/PLAN-v0.5.md](docs/PLAN-v0.5.md).)
- English and Turkish.
- About screen with security overview and links to other Veilo ecosystem products.

See [SECURITY.md](SECURITY.md) for the threat model and known limitations,
[PRIVACY.md](PRIVACY.md) for the privacy policy, and
[Security Assessment Report](docs/SECURITY-ASSESSMENT.md) for the full security audit.

## Layout

| Module | Contents |
| --- | --- |
| `core/` | Pure Kotlin/JVM, no Android dependency: OTP algorithms, Base32, `otpauth://` parsing, encrypted backup container (Argon2id + AES-256-GCM) and its JSON payload. Unit-tested. |
| `app/` | Android app (Jetpack Compose): `security/` (Keystore, biometric prompt), `data/` (account list, backup export/import; unit-tested against an in-memory key store), `ui/`. |

## Build

Requires JDK 17 and the Android SDK (API 36).

```sh
./gradlew :core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug  # debug APK in app/build/outputs/apk/debug/
```

### Signed release

Release builds are signed when a `key.properties` file exists in the project root; without
it they are left unsigned. Neither it nor the keystore is ever committed.

```properties
storeFile=upload-keystore.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

```sh
./gradlew :app:bundleRelease    # app/build/outputs/bundle/release/app-release.aab (for Google Play)
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
```

Minimum Android version is 11 (API 30), the first release where a Keystore key can
require "strong biometric or device credential".

## Roadmap

**v0.1 — Offline authenticator** ✅ *Released*

- [x] OTP core with RFC test vectors (TOTP + HOTP, SHA-1/256/512, 6–8 digits)
- [x] Hardware-backed key storage (StrongBox / TEE), biometric gate, screen protection
- [x] QR and manual enrolment, code list, tap-to-reveal
- [x] Encrypted backup (Argon2id + AES-256-GCM) export / import
- [x] Clipboard auto-clear, root / debugger detection
- [x] Edit, search, account groups, advanced manual entry
- [x] Release signing, Google Play closed testing

**v0.2 — Migration & polish** ✅ *Released*

- [x] Import from Google Authenticator, Microsoft Authenticator, Aegis
- [x] Printable PDF backup with QR codes
- [x] Pin favorite accounts to top
- [x] Countdown color warning (amber → red)
- [x] Arabic language support (English, Turkish, Arabic)
- [x] Professional About screen with usage guide
- [x] Security Assessment Report v1.0
- [x] Product landing page (auth.veilo.tr)

**v0.3 — Cloud-less encrypted sync** *(in closed testing)*

- [x] Device-to-device transfer via encrypted QR chain (no cloud) — P-256 ECDH + HKDF-SHA-256 + AES-256-GCM, key lives only on the two phones
- [x] Encrypt account metadata file (issuer/name) — AES-256-GCM under a Keystore key, plaintext file migrated and wiped on first start
- [x] Reproducible builds — see [docs/REPRODUCIBLE-BUILDS.md](docs/REPRODUCIBLE-BUILDS.md)
- [ ] Independent third-party penetration test

**v0.4 — Enterprise foundation (still offline)** *(built, in testing)*

- [x] Managed configuration (app restrictions) pushed by the MDM: hardware-bound keys only, exports / imports / PDF / transfer disabled, root or debugger blocking — see [docs/ADMIN-GUIDE.md](docs/ADMIN-GUIDE.md)
- [x] Policy status visible under the app title and in the About screen; existing exportable accounts can be moved into the security chip
- [ ] Managed Google Play availability for MDM distribution (requires the production release on Play)
- [ ] Independent third-party penetration test (carried over from v0.3)

**v0.5 — Enterprise connected**

- [x] Separate build flavour (`enterprise`) with network access; the public edition stays offline by construction — see [docs/PLAN-v0.5.md](docs/PLAN-v0.5.md)
- [x] Gateway QR enrolment: Veilo extension parsed, device key generated with attestation challenge, `/enrol/confirm` called (verified on a phone 2026-11-10)
- [x] Hardware key attestation: the Gateway verifies the Keystore chain at enrolment (Gateway 0.3, verified on a phone 2026-11-10)
- [x] Signed challenge-response: the Enterprise flavour polls its Gateway while unlocked, shows number matching and signs the answer with the device key (Gateway 0.4; device test pending)
- [ ] Centralized enrollment from the Gateway admin console

**Veilo MFA Gateway** *(separate server product, developed alongside v0.5)*

- [ ] 1.0 — privacyIDEA / multiOTP core with Veilo API layer, admin console, Docker image and .deb package
- [ ] 1.x — ISO virtual appliance for self-hosted deployment, once 1.0 is stable

**v0.6 — Passkeys & cross-platform**

- [ ] FIDO2 / Passkey support (phishing-resistant authentication)
- [ ] iOS companion app
- [ ] Desktop companion (view-only, no key storage)

**Deferred**

- Wear OS companion: small user base and a weaker security model on the watch (no StrongBox, weak lock, no screen protection). Revisited only on demand, after v0.6.

## License

[Apache License 2.0](LICENSE)
