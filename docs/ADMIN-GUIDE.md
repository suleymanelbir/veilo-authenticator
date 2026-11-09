# Administrator guide: Veilo Authenticator on managed devices

Veilo Authenticator can be distributed through your MDM and governed by organisation rules
without any change to its security model: the app still has no network permission, and rules
reach it through Android's own managed-configuration channel. The same APK serves personal and
managed devices; a device with no configuration behaves exactly like the public edition.

Requires Android 11 or later and a device enrolled in Android Enterprise, either as a fully
managed device or with a work profile.

## 1. Approve the app in Managed Google Play

1. In your EMM console (Microsoft Intune, Google Workspace, Samsung Knox Manage, VMware
   Workspace ONE, and others) open the Managed Google Play store.
2. Search for **Veilo Authenticator** (package `tr.veilo.authenticator`) and approve it.
3. Assign it to the user groups or devices that need it. Install can be made mandatory.

## 2. Set the managed configuration

Every EMM shows the same form, generated from the schema inside the app:

| Setting | Default | Effect when changed |
|---|---|---|
| Organisation name | — | Shown under the app title and on the About screen |
| Support contact | — | E-mail or phone shown on About and on the blocked screen |
| Security chip only | off | New and imported accounts are stored as non-exportable keys in the device's security hardware. Existing exportable accounts get a one-time "move into the security chip" prompt on each unlock until none remain. |
| Allow encrypted backup files | on | Off hides the passphrase-protected backup export |
| Allow importing backups and migrating from other apps | on | Off hides backup import and migration from Google, Microsoft or Aegis |
| Allow printable PDF backup | on | Off hides the PDF export |
| Allow sending accounts to another phone | on | Off: this phone cannot be the sender in a device-to-device transfer |
| Allow receiving accounts from another phone | on | Off: this phone cannot be the receiver |
| Block codes on rooted devices | off | On: instead of a warning banner, codes are not shown at all on a rooted or modified device |
| Block codes while a debugger is attached | off | On: codes are not shown while a debugger or tracer is attached |

Where to find the form:

- **Intune:** Apps → App configuration policies → Add → Managed devices → platform Android
  Enterprise → select the app → "Use configuration designer".
- **Google Workspace:** Devices → Mobile & endpoints → Apps → Web and mobile apps → the app →
  Managed configurations.
- **Knox Manage:** Application → the app → Set Configuration.

Changes reach the device within minutes; the app applies them on its next unlock, or at once
if it is open.

## 3. What a user sees

- "Managed by <organisation>" under the app title.
- Hidden menu entries for whatever you disabled; the screens cannot be reached by any other
  path either.
- With "Security chip only": the storage choice disappears from the add-account screen, and a
  dialog offers to move existing exportable accounts into the chip. Codes keep working during
  and after the move.
- About → "Organisation policy" lists the rules in force and your support contact.
- With a blocking rule and a matching risk: a full-screen notice with your support contact
  instead of the account list.

## 4. Limitations to know

- **Keys too short for the security chip.** Android's Keystore refuses HMAC keys shorter than
  64 bits. A few services issue such keys. Under "Security chip only" those accounts cannot be
  enrolled, and the user is told to ask the service for a new key or contact you. The
  move-to-chip dialog reports them as needing re-enrolment.
- **Fixed lock settings.** Authentication is required every 2 minutes of key use and may be
  biometric or device credential. These are properties of the keys themselves and cannot be
  changed by configuration.
- **No reporting.** The app is offline, so it cannot tell your console that a rule is in force.
  Verify on a test device. Remote attestation and reporting arrive with the connected
  enterprise edition and Veilo MFA Gateway.
- **Rooted-device detection is heuristic.** It catches ordinary root setups; a sophisticated
  hiding tool can evade it. Hardware attestation (connected edition) is the robust answer.

## 5. Testing without an EMM subscription

Google's **Test DPC** app (Play Store) acts as a device policy controller for development:

1. Install Test DPC and, on a test phone, create a work profile or set it as device owner.
2. Install Veilo Authenticator from Play into that profile.
3. In Test DPC: Manage app restrictions → select the app → set values → Save.
4. Open the app; it shows "Managed by …" and applies the rules.
