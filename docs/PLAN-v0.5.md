# v0.5 — Enterprise connected: technical plan and status

**Goal.** A second build flavour, *Enterprise*, that talks to Veilo Gateway. The public edition
keeps no network permission and is unchanged by anything here: every Gateway feature lives in
`app/src/enterprise/` or behind the `Edition` object that each flavour provides.

**Done looks like.** An admin creates a token in the Gateway console; the QR carries the Veilo
extension; the Enterprise app imports the secret into the security hardware, generates an
attested device key, confirms with the Gateway and the device appears in the console. Later
items (attestation verification, challenge-response) build on that device key.

---

## 1. Flavours

| | `public` | `enterprise` |
|---|---|---|
| applicationId | `tr.veilo.authenticator` | `tr.veilo.authenticator.enterprise` |
| INTERNET permission | removed (`src/public/AndroidManifest.xml`, `tools:node="remove"`) | declared (`src/enterprise/AndroidManifest.xml`) |
| `Edition.CONNECTED` | false | true |
| `Edition.gateway()` | null | `HttpGatewayClient` |
| Plain-http Gateway | never | debug builds only (lab) |
| Distribution | Google Play | Managed Google Play (private app) |

The main source set never references enterprise classes directly; it calls `Edition` and
`DeviceKeyFactory`, which both source sets define with the same signatures.

## 2. Enrolment with the Gateway (work items 1 and 4) — **implemented 2026-11-10**

1. `core/VeiloEnrolment.parse(uri)` reads `veilo_gw`, `veilo_ticket`, `veilo_keylevel` from
   an otpauth URI. Absent → null (plain QR). Half present or unsafe (non-https Gateway, bad
   ticket, unknown level) → rejected as a broken QR. Vectors: `core/src/test/resources/
   protocol-vectors.json`, a copy of the Gateway repository's `docs/protocol-vectors.json`.
2. The public edition parses the extension for validity only and enrols a plain TOTP account.
3. The Enterprise flavour forces hardware-bound storage for a Gateway QR, stores the account,
   generates an EC P-256 device key in the Keystore (StrongBox first, then TEE) with
   attestation challenge `SHA-256("veilo-enrol" || ticket)`, and POSTs `/enrol/confirm` with
   the ticket, the Keystore-reported level of the OTP key, the app identity (package, version,
   signing certificate digest), the device public key as JWK, the attestation chain as PEM and
   the managed-configuration snapshot.
4. Enrolment is all-or-nothing. `recorded` → account kept, "Registered with your organisation".
   Refused (the Gateway has disabled the token) or unreachable → account and device key removed,
   reason shown; the user rescans online or asks for a new QR.

Files: `core/.../VeiloEnrolment.kt`, `app/src/main/.../gateway/GatewayClient.kt` (interface and
result types), `app/src/enterprise/.../gateway/{GatewayJson,HttpGatewayClient,DeviceKey,
AppIdentityProvider}.kt`, `edition/{Edition,DeviceKeyFactory}.kt` per flavour,
`AppViewModel.confirmWithGateway`, `Policy.toSnapshot`.

Tests: `core` VeiloEnrolmentTest (vectors), `testEnterprise` GatewayJsonTest and
DeviceKeyEncodingTest, PolicyTest snapshot case. Device test: pending (needs a Gateway the
phone can reach; the lab binds to 127.0.0.1 on the server).

## 3. Still open in v0.5

- **Attestation verification** happens on the Gateway (its 0.3). The app already sends the
  chain. Note: the attestation proves where the *device key* lives; the OTP key level is the
  Keystore's own report of the HMAC key. Both are hardware-bound by the same Keystore, so the
  Gateway treats the device key's attestation as evidence for the OTP key as well.
- **Challenge-response** (Gateway 0.4) — built 2026-11-10. A confirmed account keeps a
  `GatewayBinding` (Gateway URL, device id). While unlocked, the Enterprise flavour polls
  `GET /device/challenges` on each bound Gateway every 4 s, authenticating with
  `Authorization: Veilo-Device <device_id>.<ts>.<sig>` (signature of
  `veilo-device|device_id|ts|METHOD|path` by the device key). A pending challenge opens a
  number-matching dialog with the three candidates; the tap signs
  `nonce|rp_id|number|device_id` and POSTs `/device/challenges/{id}/response`, one attempt.
  "Not me" sends nothing: the challenge expires on the Gateway and the login fails. Formats
  live in `core/VeiloChallenge.kt` and are checked against the shared protocol vectors
  (the vector signatures verify with the vector public key). QR fallback for air-gapped
  logins is not built: it needs a short, typeable response format first (Gateway O-item).
- **Device heartbeat and revocation** (Gateway 0.5) — built 2026-11-10. The challenge poll
  carries a small device-state header (app version, OS patch level, integrity risks, model;
  PROTOCOL §5.1), so the Gateway console shows `last_seen` and risks per device. When a help-desk
  user revokes a device, the Gateway answers `401 device_revoked`; the app marks the binding as
  revoked, never polls it again, tells the user once, and the account card reads "Revoked by your
  organisation". The sign-in dialog shows the seconds left and closes itself when the request
  expires; a touch outside it no longer counts as "not me". Verified with a real phone.
- **Centralized enrolment** is done on the Gateway side (0.5): the console issues the QR, in
  one form together with the directory user.
- **TLS pinning through the QR** (Gateway D-18) — built 2026-11-10. `veilo_pins` in the QR is
  stored on the `GatewayBinding`; `PinnedHostnameVerifier` requires, inside the handshake, that the
  chain the system trust store built contains a pinned key (`core/TlsPins.kt`, shared vectors). A
  mismatch sends nothing; enrolment shows its own message. No pins in the QR = system trust only.
- **Managed Google Play** private-app listing for the Enterprise flavour.

## 4. What does not change

Codes are computed offline in both flavours. The Enterprise flavour talks only to the
Gateway hosts its QR codes named: once at enrolment, and, for a bound account, a short
poll every few seconds while the app is unlocked and in front. Nothing runs in the
background, there is no push service, and the public edition never opens a socket. No
telemetry.
