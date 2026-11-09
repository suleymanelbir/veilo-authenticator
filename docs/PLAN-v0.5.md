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
- **Challenge-response** (Gateway 0.4): sign `nonce || rp_id || number || device_id` with the
  device key, HTTPS polling while unlocked, QR fallback.
- **Certificate pinning per tenant** through the QR (Gateway 0.6).
- **Managed Google Play** private-app listing for the Enterprise flavour.

## 4. What does not change

Codes are computed offline in both flavours. The Enterprise flavour makes exactly one network
call today, right after a Gateway QR is scanned, and only to the host that QR names. No
telemetry, no background traffic.
