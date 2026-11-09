# Security Assessment Report

**Product:** Veilo Authenticator v0.3.0 (Android)  
**Package:** `tr.veilo.authenticator`  
**Assessment Date:** November 2026  
**Assessor:** Veilo Security Team  
**Classification:** Public  
**Report Version:** 1.1 (updated for v0.3.0: metadata encryption, device-to-device transfer)

---

## Executive Summary

Veilo Authenticator is an offline TOTP/HOTP authenticator for Android that stores cryptographic secrets in hardware-backed secure storage. This report documents the security architecture, threat model, attack surface analysis, and mitigation controls implemented in the application.

**Overall Risk Rating: LOW**

The application implements defense-in-depth across 11 security layers. No critical or high-severity vulnerabilities were identified. The architecture follows a zero-trust, offline-first design with no network attack surface.

| Severity | Findings |
|----------|----------|
| Critical | 0 |
| High | 0 |
| Medium | 0 |
| Low | 1 (accepted risk, documented below) + 1 resolved in v0.3.0 |
| Informational | 4 |

---

## 1. Application Overview

### 1.1 Purpose
Two-factor authentication code generator supporting TOTP (RFC 6238) and HOTP (RFC 4226) with SHA-1, SHA-256, and SHA-512 algorithms. 6–8 digit codes, configurable periods.

### 1.2 Architecture

```
┌─────────────────────────────────────────────────┐
│                   UI Layer                       │
│         Jetpack Compose · FLAG_SECURE            │
├─────────────────────────────────────────────────┤
│               ViewModel Layer                    │
│    Code generation · Timer · Memory zeroing      │
├─────────────────────────────────────────────────┤
│              Repository Layer                    │
│   Account CRUD · Import/Export · Deduplication   │
├──────────────────┬──────────────────────────────┤
│  Secret Store    │     Backup Codec             │
│  Android KS /    │     Argon2id (64MB, 3p)      │
│  StrongBox       │     AES-256-GCM              │
├──────────────────┴──────────────────────────────┤
│          Android Keystore / TEE / StrongBox      │
└─────────────────────────────────────────────────┘
```

### 1.3 Key Properties
- **Minimum SDK:** Android 11 (API 30)
- **Internet Access:** Explicitly removed (`tools:node="remove"`)
- **Cloud Backup:** Disabled (`allowBackup="false"`, `dataExtractionRules` excludes all)
- **Code Obfuscation:** R8/ProGuard enabled with `minifyEnabled` and `shrinkResources`
- **Languages:** English, Turkish, Arabic

---

## 2. Threat Model

### 2.1 Assets Under Protection

| Asset | Sensitivity | Storage |
|-------|-------------|---------|
| OTP shared secrets | CRITICAL | Android Keystore (HW-bound) or AES-256-GCM encrypted (exportable) |
| Account metadata (issuer, name) | LOW | App-private file, AES-256-GCM under a Keystore key (v0.3.0; unencrypted JSON before) |
| Generated OTP codes | HIGH (short-lived) | Memory only, auto-zeroed |
| Backup passphrase | HIGH (transient) | Memory only during export/import |
| Backup file | CRITICAL | User-chosen external storage, Argon2id + AES-256-GCM |
| Transfer frames (QR) | CRITICAL (transient) | Shown on screen only; AES-256-GCM under an ECDH-agreed key that exists on the two phones for the duration of the transfer |

### 2.2 Threat Actors

| Actor | Capability | Likelihood |
|-------|-----------|------------|
| Lost/stolen device — casual finder | Physical access, no unlock | HIGH |
| Lost/stolen device — sophisticated attacker | Physical access, forensic tools | MEDIUM |
| Malicious app on same device (no root) | Sandbox escape attempts, clipboard reading | MEDIUM |
| Malicious app with root | Full filesystem and memory access | LOW |
| Network attacker | MITM, traffic interception | N/A (no network) |
| Shoulder surfing | Visual observation of screen | MEDIUM |
| Compromised backup file | Offline brute-force of passphrase | MEDIUM |

### 2.3 Attack Surface

| Surface | Exposure | Status |
|---------|----------|--------|
| Network | **None** — INTERNET permission removed | ELIMINATED |
| IPC / Exported Components | Launcher Activity only | MINIMAL |
| File System | App-private storage, Keystore-encrypted | HARDENED |
| Clipboard | 30s auto-clear, IS_SENSITIVE flag | MITIGATED |
| Screen | FLAG_SECURE, recents disabled | MITIGATED |
| QR Scanner | Camera frames processed locally, never stored | CONTROLLED |
| Backup File | Argon2id + AES-256-GCM, user-managed | HARDENED |
| Memory | Secrets zeroed after use | MITIGATED |

---

## 3. Security Controls Assessment

### 3.1 Cryptographic Key Storage

**Control:** Android Keystore with hardware-backed protection.

**Implementation:**
- **Hardware-bound mode:** OTP secret imported as non-exportable HMAC key. HMAC computed inside secure hardware — secret never returns to app memory.
- **Exportable mode:** OTP secret encrypted with AES-256-GCM wrapping key held in Keystore. Secret in app memory only during code computation.
- **StrongBox:** Used on Android 12+ where the device has a StrongBox chip supporting the required HMAC algorithm. Falls back to TEE.
- **Authentication:** Every key requires `setUserAuthenticationRequired(true)` with `setUserAuthenticationParameters(120, BIOMETRIC_STRONG | DEVICE_CREDENTIAL)`.

**Assessment:** STRONG — Secrets at rest are protected by hardware-backed isolation. Hardware-bound secrets cannot be extracted even with root access.

### 3.2 Biometric / Credential Gate

**Control:** Keystore-enforced authentication window.

**Implementation:**
- Authentication enforced by Keystore hardware, not app logic.
- 120-second window after strong biometric or device credential.
- Operations outside window throw `UserNotAuthenticatedException`; app re-prompts or locks.
- App locks on every `onStop()` (leaving foreground).

**Assessment:** STRONG — Cannot be bypassed by app-level tampering; enforcement is in the TEE/StrongBox.

### 3.3 Screen Protection

**Control:** FLAG_SECURE and recents screenshot prevention.

**Implementation:**
- `window.setFlags(FLAG_SECURE, FLAG_SECURE)` in `MainActivity.onCreate()`
- `setRecentsScreenshotEnabled(false)` on API 33+
- Prevents screenshots, screen recording, casting, and app-switcher thumbnail.

**Assessment:** STRONG — OS-level enforcement, cannot be bypassed without root.

### 3.4 Clipboard Security

**Control:** Auto-clear with sensitive flag.

**Implementation:**
- `ClipData` marked with `PersistableBundle(EXTRA_IS_SENSITIVE = true)` — tells keyboard apps not to learn or cache.
- 30-second timer clears clipboard via `ClipboardManager.setPrimaryClip()`.
- Backup `AlarmManager` alarm fires if the app is killed before timer completes.
- On API 33+, Android auto-clears sensitive clips; the app's own timer provides defense on older versions.

**Assessment:** ADEQUATE — Samsung Keyboard's clipboard history may retain copies despite IS_SENSITIVE (OS vendor behavior outside app control). Codes expire in 30 seconds, limiting exposure.

### 3.5 Network Isolation

**Control:** INTERNET permission removed from merged manifest.

**Implementation:**
```xml
<uses-permission android:name="android.permission.INTERNET"
    tools:node="remove" />
```
- Strips INTERNET from the merged manifest even if a dependency tries to add it.
- No analytics, crash reporting, Firebase, Sentry, or any third-party SDK.
- No HTTP client, WebView, or socket usage anywhere in the codebase.

**Assessment:** MAXIMUM — The app physically cannot make network connections. Zero network attack surface.

### 3.6 Memory Hygiene

**Control:** Secret material zeroed after use.

**Implementation:**
- `OtpSpec.secret.fill(0)` called after enrollment (`AppViewModel:258`)
- `spec.secret.fill(0)` after import (`AccountRepository:86`)
- Backup passphrase `CharArray` zeroed after use (`AppViewModel:313,425`)
- Decrypted backup payload zeroed after parsing (`AppViewModel:414`)
- Hardware-bound secrets never enter app memory at all.

**Assessment:** ADEQUATE — ByteArrays are zeroed, but JVM may retain copies during garbage collection. This is an inherent JVM limitation, mitigated by the short lifetime of secret material in memory.

### 3.7 Backup Encryption

**Control:** Argon2id + AES-256-GCM authenticated encryption.

**Implementation:**
- **KDF:** Argon2id with 64 MiB memory, 3 iterations, 4 parallelism
- **Cipher:** AES-256-GCM with 12-byte random nonce
- **AAD:** 42-byte header (magic, version, KDF params, salt, nonce) authenticated
- **Salt:** 32 bytes from `SecureRandom`
- **Minimum passphrase:** 8 characters, NFC-normalized
- **Import bounds:** File size capped at 1 MiB, KDF parameters bounded to prevent resource exhaustion

**Assessment:** STRONG — Argon2id with 64 MiB makes offline brute-force extremely expensive. A strong passphrase makes the backup practically unbreakable.

### 3.8 Root and Debugger Detection

**Control:** Runtime integrity checks with user warning.

**Implementation:**
- Checks for `su` binary in common paths
- Checks `ro.build.tags` for `test-keys`
- Checks `Debug.isDebuggerConnected()` and `/proc/self/status` TracerPid
- Runs on every unlock, displayed as a non-blocking warning banner.

**Assessment:** INFORMATIONAL — Advisory only; a sophisticated root kit can hide itself. Device integrity attestation planned for enterprise edition (Phase 3).

### 3.9 Data Extraction Prevention

**Control:** Cloud backup and device transfer disabled.

**Implementation:**
```xml
android:allowBackup="false"
android:fullBackupContent="false"
android:dataExtractionRules="@xml/data_extraction_rules"
```
- `data_extraction_rules.xml` excludes all domains from both cloud backup and device-to-device transfer.

**Assessment:** STRONG — Prevents extraction through ADB backup, Google cloud backup, and device migration.

### 3.10 Code Obfuscation

**Control:** R8/ProGuard in release builds.

**Implementation:**
- `isMinifyEnabled = true`
- `isShrinkResources = true`
- Optimized ProGuard configuration (`proguard-android-optimize.txt`)
- Dependency info blobs excluded from APK/AAB (`dependenciesInfo` disabled)

**Assessment:** ADEQUATE — Raises the bar for reverse engineering. Not a security boundary, but increases effort required.

### 3.11 QR Scanner Isolation

**Control:** Camera frames processed locally, never stored.

**Implementation:**
- Uses `zxing-android-embedded` library for barcode scanning.
- Camera permission declared with `android:required="false"`.
- Frames decoded in memory, never written to storage or transmitted.
- Scanner activity locked to sensor orientation only.

**Assessment:** STRONG — No data exfiltration path from camera.

---

## 4. Migration Security

### 4.1 Google / Microsoft Authenticator Import
- Parses `otpauth-migration://offline?data=BASE64` protobuf format.
- Minimal protobuf decoder (no external library) — reduces supply chain risk.
- Secrets extracted, imported as exportable accounts, then zeroed from memory.

### 4.2 Aegis Import
- Parses plaintext JSON export (encrypted Aegis vaults not supported).
- Uses `kotlinx.serialization` with `ignoreUnknownKeys` — resilient to malformed input.
- Invalid entries skipped silently; valid entries imported.

### 4.3 PDF Backup
- Generates `otpauth://` QR codes using Android's built-in `PdfDocument` API.
- **Warning:** PDF contains secrets in plain form. Physical security of the printed document is the user's responsibility. Red warning displayed prominently in UI.

### 4.4 Device-to-Device Transfer (v0.3.0)

Moves accounts between two phones with QR codes only; no file, no network, no cloud.

- **Key agreement:** the receiving phone generates an ephemeral P-256 key pair and shows its public key as a QR code. The sending phone scans it, generates its own ephemeral pair and derives a 256-bit key with ECDH followed by HKDF-SHA-256 (salt = both public keys, fixed info string).
- **Encryption:** the backup payload is encrypted with AES-256-GCM; both public keys are bound in as associated data. The ciphertext is cut into numbered frames tagged with a hash of the whole, so frames from a restarted or different transfer cannot be mixed in.
- **Curve validation:** a scanned public key is accepted only if it is a point on P-256, which blocks invalid-curve attempts from a hostile QR code.
- **What an observer gets:** someone filming the sender's screen obtains ciphertext only. The receiver's private key never leaves its process memory and is discarded when the screen is left or the app locks. `FLAG_SECURE` additionally blocks screenshots and casting of the sender's frames.
- **Residual risk:** a person who controls both phones at that moment can run the transfer, which is the same as controlling the unlocked phone. There is no sender authentication: the receiver imports whatever decrypts under its key, which requires the sender to have scanned the receiver's screen in person. Hardware-bound accounts are excluded, as with every export.

---

---

## 5. Dependency Analysis

| Dependency | Version | Purpose | Risk |
|-----------|---------|---------|------|
| AndroidX Core/Compose/Lifecycle | 2026.03 | UI framework | LOW — Google-maintained |
| AndroidX Biometric | 1.1.0 | Biometric prompt | LOW — Stable release |
| Bouncy Castle | 1.86 | Argon2id KDF | LOW — Industry standard |
| kotlinx-serialization | 1.10.0 | JSON parsing | LOW — JetBrains-maintained |
| zxing-android-embedded | 4.3.0 | QR scanning | LOW — Widely audited |

No dependencies with known CVEs at time of assessment. No dependencies require network access.

---

## 6. Findings

### 6.1 Low Severity

**L-01: Account metadata stored unencrypted** — *Resolved in v0.3.0*
- **Description:** Up to v0.2.0, service names and account labels were stored in plaintext JSON in app-private storage.
- **Impact:** An attacker with root access could learn which services a user had enrolled, even without accessing the secrets.
- **Resolution:** The account list is now one file encrypted with AES-256-GCM under a dedicated Android Keystore key (`vault.v1`), with a format header authenticated as associated data. The key does not require user authentication, by design: the list is read on every unlock, and the secrets inside stay wrapped under the authentication-bound key regardless. On the first start after the update the plaintext file is re-encrypted, overwritten and deleted. Writes are atomic (temporary file + rename).

**L-02: JVM memory copy limitation**
- **Description:** The JVM does not guarantee that a `ByteArray.fill(0)` call erases all copies of the data in memory.
- **Impact:** Secret material may persist briefly in garbage-collected heap.
- **Recommendation:** Inherent JVM limitation. Hardware-bound mode eliminates this entirely. For exportable mode, the window is minimized to milliseconds during code computation.

### 6.2 Informational

**I-01:** Samsung Keyboard clipboard history may retain copied codes despite `IS_SENSITIVE` flag. Codes expire in 30 seconds. Vendor-specific behavior outside app control.

**I-02:** Root detection is advisory only. A sophisticated root kit can evade the checks. Hardware key attestation planned for enterprise edition.

**I-03:** The app has not undergone an independent third-party security audit. This report is a self-assessment by the development team.

**I-04:** StrongBox usage depends on device hardware and algorithm support. Devices without StrongBox fall back to TEE, which is still hardware-backed but less isolated.

---

## 7. Compliance Mapping

| Standard | Relevant Controls | Status |
|----------|------------------|--------|
| OWASP MASVS L1 | Storage, Crypto, Auth, Network, Platform | PASS |
| OWASP MASVS L2 | Resilience (obfuscation, root detection) | PARTIAL |
| ISO 27001 A.8.5 | Secure authentication | ALIGNED |
| ISO 27001 A.8.24 | Cryptography use | ALIGNED |
| NIST SP 800-63B | Multi-factor authentication | ALIGNED |
| KVKK / GDPR | Data minimization, no processing | ALIGNED (no personal data collected, processed, or transmitted) |

---

## 8. Recommendations for Future Versions

| Priority | Recommendation | Target |
|----------|---------------|--------|
| HIGH | Independent third-party penetration test | v0.3 |
| MEDIUM | Hardware key attestation for enterprise | v0.4 |
| LOW | Publish reproducible-build attestations with each release (procedure in place since v0.3.0) | v0.5 |
| LOW | FIDO2/Passkey support (phishing resistance) | v0.5 |

---

## 9. Conclusion

Veilo Authenticator v0.3.0 demonstrates a strong security posture for a mobile authenticator application. The combination of hardware-backed key storage, zero network access, authenticated encryption for backups, and defense-in-depth across 11 layers places it significantly above the security baseline of typical authenticator apps.

One low-severity finding was resolved in v0.3.0; the remaining one is an accepted JVM limitation with documented mitigations. The four informational findings are either platform limitations or planned improvements for future versions.

**The application is assessed as suitable for production use.**

---

*This report was generated by the Veilo Security Team as a self-assessment. For maximum assurance, an independent third-party audit is recommended and planned for a future release.*

**Document Hash (SHA-256):** To be computed after finalization  
**Veilo Technology — https://veilo.tr**
