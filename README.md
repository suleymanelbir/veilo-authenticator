# Axon Gate

> The product name is defined in one place (`app_name` in
> `app/src/main/res/values/strings.xml`). The application ID is `com.selbir.axongate`;
> the code keeps the `com.selbir.authenticator` namespace.
>
> An *axon* carries signals across the nervous system; a *gate* controls which signals
> pass through. An authenticator works the same way — it holds a shared secret and
> opens the gate only when the right code is presented.

An offline, open-source one-time-password authenticator for Android whose keys live in
the device's secure hardware.

**Status: Phase 1 skeleton.** It builds and the OTP core is tested against the RFC
vectors, but it has not yet been exercised on a physical device or independently
reviewed. Do not rely on it for real accounts yet.

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
- No network access: the `INTERNET` permission is stripped from the merged manifest.
  Android cloud backup and device-to-device transfer are disabled.
- English and Turkish.

See [SECURITY.md](SECURITY.md) for the threat model and known limitations, and
[PRIVACY.md](PRIVACY.md) for the privacy policy.

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

**Phase 1 — offline authenticator (this repository)**

- [x] OTP core with RFC test vectors
- [x] Hardware-backed key storage, biometric gate, screen protection
- [x] QR and manual enrolment, code list, HOTP
- [x] Encrypted backup container format (`core/BackupCodec`)
- [x] Export / import screens for backup-capable accounts
- [ ] Instrumented tests on real devices (Keystore, biometric flows)
- [x] Clipboard auto-clear
- [x] StrongBox where available, key security level shown per account
- [x] Root / debugger warning
- [ ] Tap-to-reveal
- [ ] Edit / reorder / search accounts, advanced manual entry (algorithm, digits, period)
- [x] Release signing
- [ ] Reproducible builds, Play closed testing

**Phase 2** — server side: PAM integration, recovery codes, central verification service.

**Phase 3** — enterprise edition (separate build flavor with network access): per-device
key pair, hardware key attestation, signed challenge-response, admin console.

**Phase 4** — passkeys, iOS, independent security audit.

## License

[Apache License 2.0](LICENSE)
