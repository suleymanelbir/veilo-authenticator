# Security

## Reporting a vulnerability

Please report privately through the repository's **Security → Report a vulnerability**
page rather than a public issue. Include the app version, device model and Android
version.

## Threat model

### What the design protects against

| Threat | Mitigation |
| --- | --- |
| Secrets copied off a lost, stolen or seized phone | Secrets are Keystore keys, or encrypted under one. Device-bound secrets cannot leave the secure hardware at all. |
| Another app, or malware without root, reading secrets | App-private storage; no secret stored in plaintext. The only exported components are the launcher activity and AndroidX's profile-installer receiver, which only the shell can call (`DUMP` permission). |
| Someone holding the unlocked phone opening the app | Keys require a fresh strong-biometric or device-credential authentication, enforced by the Keystore. |
| Codes leaking through screenshots, recordings, casting or the recents view | `FLAG_SECURE` and disabled recents screenshots. |
| Secrets leaving the device through cloud backup or phone-to-phone transfer | Both disabled in the manifest. |
| The app phoning home | No `INTERNET` permission in the merged manifest. |
| Offline guessing of a backup passphrase | Argon2id (64 MiB, 3 passes) and authenticated encryption; parameters are bounded on import. The app requires at least 8 characters. |
| A hostile or damaged file offered for import | File size and KDF cost are bounded before any work is done; the contents are parsed only after the container authenticates; an import is all-or-nothing. |

### What it does not protect against

- **Phishing.** OTP codes can be typed into a fake site. This is inherent to TOTP/HOTP;
  phishing resistance is planned through signed challenge-response and passkeys.
- **A compromised operating system.** With root or a kernel exploit an attacker can
  drive the Keystore while the user is authenticated, read backup-capable secrets from
  memory while a code is computed, and read the screen. Device-bound secrets still
  cannot be extracted, only used on that device.
- **The verifying server.** The server holds the same shared secret.
- **A weak backup passphrase.** A backup file is written wherever the user chooses and
  is then outside the app's and the device's protection: the passphrase is all that
  guards it. Argon2id slows guessing down but cannot rescue a guessable passphrase.
- **Loss of the screen lock.** Removing the device's screen lock destroys all
  authentication-bound keys by design. Device-bound accounts must then be re-enrolled.

### Known limitations of the current skeleton

- Not yet tested on physical devices and not independently audited.
- Account labels (service and account names) are stored unencrypted in app-private
  storage; only the secrets are encrypted.
- The JVM gives no guarantee that a wiped byte array has no other copies. For
  backup-capable accounts the secret is briefly in app memory; device-bound accounts
  avoid this entirely.
- The backup passphrase is typed into a text field and so exists as an immutable string
  that cannot be wiped; while a backup is written or read, the secrets in it also pass
  through such strings (JSON, Base32). They are unreachable once the screen closes but
  stay in memory until the garbage collector reuses it.
- Keys use the TEE; StrongBox is not requested yet.
- A copied code is cleared from the clipboard after 30 seconds, with limits Android
  imposes: in the background the app cannot see what the clipboard holds, so it clears
  whatever is there; the backup alarm may run late; and a keyboard's or vendor's own
  clipboard history is out of reach.
- No root or tamper detection. Client-side checks are bypassable; device integrity will
  be verified server-side through hardware key attestation in the enterprise edition.
