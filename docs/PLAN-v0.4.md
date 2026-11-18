# v0.4 — Enterprise foundation: technical plan

**Goal.** A company's IT department can distribute Veilo Authenticator through its MDM and
enforce how it is used, while the app stays offline by construction. The public edition and
the managed edition are the same APK: policy arrives through Android's managed-configuration
channel and the app locks itself down accordingly. No policy present means today's behaviour.

**Done looks like.** On a work-profile device enrolled in an MDM (tested with Google's TestDPC
during development), the admin sets "hardware-bound keys only" and "exports disabled"; the app
then offers only hardware-bound enrolment, hides the export / PDF / transfer menu items, shows
"Managed by <organisation>" with the active rules, and offers to move existing exportable
accounts into the security chip. All of this is covered by unit tests on the policy model and
by a device test against TestDPC.

---

## 1. Policy model

New package `com.selbir.authenticator.policy`.

```kotlin
data class Policy(
    val managed: Boolean,                    // any restriction present
    val organizationName: String?,           // "Acme A.Ş."
    val supportContact: String?,             // e-mail / phone shown in About
    val hardwareBoundOnly: Boolean,          // enrolment must use Protection.HARDWARE_BOUND
    val allowBackupExport: Boolean,          // encrypted .otpb export
    val allowBackupImport: Boolean,          // .otpb import + migration from other apps
    val allowPdfExport: Boolean,
    val allowTransferSend: Boolean,          // device-to-device, this phone as sender
    val allowTransferReceive: Boolean,
    val blockOnRootedDevice: Boolean,        // DeviceRisk.ROOTED → blocked screen instead of a banner
    val blockOnDebugger: Boolean,
) { companion object { val NONE = Policy(managed = false, …all allowed…) } }
```

- `Policy.from(map: Map<String, Any?>)` parses a flat key/value map so the parser is a pure
  Kotlin unit under test. The Android side converts the `Bundle` from
  `RestrictionsManager.getApplicationRestrictions()` into that map.
- Unknown keys are ignored; missing keys take the permissive default. A policy can only ever
  remove capabilities, never add one the public edition lacks.
- `PolicySource` (interface) → `RestrictionsPolicySource(context)` reads the bundle, and
  re-reads on `Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED` (broadcast registered at
  runtime while the app is in the foreground; also re-read in `onAuthenticated`). Exposed as a
  `StateFlow<Policy>` from `AuthenticatorApp`.

### Managed-configuration schema

`res/xml/app_restrictions.xml`, referenced from the manifest:

```xml
<meta-data android:name="android.content.APP_RESTRICTIONS" android:resource="@xml/app_restrictions"/>
```

| Key | Type | Default | Shown to the admin as |
|---|---|---|---|
| `organization_name` | string | — | Organisation name shown in the app |
| `support_contact` | string | — | Support contact shown in About |
| `hardware_bound_only` | bool | false | Keys may only be stored in the security chip |
| `allow_backup_export` | bool | true | Allow encrypted backup files |
| `allow_backup_import` | bool | true | Allow importing backups and migrating from other apps |
| `allow_pdf_export` | bool | true | Allow printable PDF backup |
| `allow_transfer_send` | bool | true | Allow sending accounts to another phone |
| `allow_transfer_receive` | bool | true | Allow receiving accounts from another phone |
| `block_on_rooted_device` | bool | false | Refuse to show codes on a rooted device |
| `block_on_debugger` | bool | false | Refuse to show codes while a debugger is attached |

Titles and descriptions come from string resources (EN/TR/AR), so Intune, Google Workspace and
Knox show them in the admin's language. Google Play reads this file and renders the managed
configuration form automatically; no Play Console work is needed beyond publishing.

## 2. Enforcement points (existing code touched)

| Policy | Where | Change |
|---|---|---|
| `hardwareBoundOnly` | `AddAccountScreen` / `AddFormState` | Protection selector hidden; `protection` fixed to `HARDWARE_BOUND`. If the Keystore refuses the key (secret shorter than 64 bits) the error says the organisation's rules do not allow this account, instead of silently falling back. |
| `hardwareBoundOnly` | `AccountRepository.importAll` | Gains a `protection` parameter. Under the policy, backup import / migration / transfer-receive enrol hardware-bound; entries the Keystore refuses are reported as "rejected by policy" in the result. |
| `hardwareBoundOnly` | new `AccountRepository.moveToHardware(id)` | Unwraps the exportable secret, imports it as an HMAC key, rewrites the account with `protection = HARDWARE_BOUND, wrappedSecret = null`. Rolls back the Keystore entry if the save fails (same pattern as `add`). Used by the "move existing accounts into the chip" prompt below. |
| `allow*` flags | `AccountListScreen` menu, `AppViewModel.navigate` | Menu items hidden; `navigate` refuses the screen (defence in depth: a stale UI cannot reach a forbidden screen). Transfer role buttons hidden individually. |
| `blockOnRootedDevice`, `blockOnDebugger` | `AppRoot` | When `DeviceIntegrity.check()` reports the risk and the policy blocks it, a full-screen `PolicyBlockedScreen` replaces the list (no codes computed). Without the policy the existing advisory `RiskBanner` stays. |
| `organizationName`, `supportContact` | `AccountListScreen` top bar subtitle, `AboutScreen` | "Managed by Acme" line; new About section "Organisation policy" listing the active rules in plain language and the support contact. |

### Existing exportable accounts under `hardwareBoundOnly`

When the policy first appears (or the app starts with it) and exportable accounts exist, a
one-time dialog lists them and offers "Move into the security chip". Accounts whose secret is
too short for the Keystore are listed as needing re-enrolment. Codes keep working in the
meantime; the policy affects storage, not display. The dialog can be dismissed and comes back
on the next unlock until no exportable account remains.

## 3. What does NOT change

- No `INTERNET` permission. Managed configuration is delivered by Android itself.
- Keystore key parameters (120 s authentication validity, biometric-or-credential) are fixed at
  key creation and are not made configurable: a policy could not change existing keys, and a
  half-applied rule is worse than none. Documented in the admin-facing text.
- `FLAG_SECURE`, clipboard clearing and the lock-on-background rule are already at their
  strictest and are not policy-controlled.
- The public edition's behaviour with no policy present is byte-for-byte today's behaviour.

## 4. Distribution: Managed Google Play

- Enterprises can only approve apps that are **publicly published** on Play (or private to
  their own organisation). The closed Alpha is not enough, so v0.4 is gated on the production
  release (the 12-tester / 14-day requirement must be met first).
- Nothing else is needed in Play Console: the managed configuration schema is read from the
  APK, the data-safety form is unchanged (still "no data collected"), and the work-profile
  install uses the same listing.
- A short admin guide, `docs/ADMIN-GUIDE.md`: how to approve the app in Managed Google Play,
  where the configuration form appears in Intune / Workspace / Knox, what each key does, and
  the known limitation about key parameters.

## 5. Testing

- **Unit:** `PolicyTest` (defaults, parsing, unknown keys, type mismatches), repository tests
  for `importAll(protection)` and `moveToHardware` (success, Keystore refusal, rollback on save
  failure), `AddFormState` under policy.
- **Device, without an MDM subscription:** install Google's **TestDPC** from Play, set it as
  device owner or create a work profile, push managed configuration from its UI to the app and
  walk the checklist: menu items hidden, enrolment forced to hardware, move-to-chip prompt,
  blocked screen on a rooted device (emulator with root is enough), About shows the policy.
- **Device, real MDM:** one of Google Workspace (has a free trial) or Intune, to confirm the
  configuration form renders from the schema.

## 6. Work packages and order

| # | Package | Depends on | Size |
|---|---|---|---|
| 1 | Policy model + parser + `RestrictionsPolicySource` + schema XML + strings | — | S |
| 2 | Menu / navigation / transfer gating; organisation line; About section | 1 | S |
| 3 | `hardwareBoundOnly`: enrolment, `importAll(protection)`, `moveToHardware`, prompt | 1 | M |
| 4 | Root / debugger blocking screen | 1 | S |
| 5 | TestDPC device test pass; admin guide; security assessment v1.2 (policy section) | 2–4 | S |
| 6 | Production release on Play (prerequisite for Managed Google Play approval) | tester rule | external |
| 7 | Independent penetration test package (carried from v0.3): debug APK, crypto design note, test accounts, scope | — | S |

Packages 1–4 are one release: versionCode 11, versionName 0.4.0. Package 7 can run in parallel.

## 7. Risks

- **Short secrets vs. hardware-only.** Some services issue 10-byte secrets, which the Keystore
  accepts, but a few use shorter ones. Under the policy those accounts cannot be enrolled; the
  message must say so clearly and the admin guide must list the limitation.
- **OEM differences.** Samsung Knox delivers managed configuration through the same Android API
  but its console labels differ; verify with a Knox trial if a customer asks.
- **Policy arrives after accounts exist.** Handled by the move-to-chip prompt; the risk is a
  user dismissing it forever, hence it returns on every unlock while exportable accounts remain.
- **No way to prove enforcement to the admin** in v0.4 (the app is offline). Attestation and
  reporting are v0.5 / Gateway features; the admin guide says so.
