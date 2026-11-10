# Veilo Enterprise on Managed Google Play

The Enterprise edition (`tr.veilo.authenticator.enterprise`, label "Veilo Enterprise") is a
separate app from the public Veilo Authenticator: its own package, its own upload key
(`enterprise-upload-keystore.jks` + `key-enterprise.properties`, never committed; certificate
SHA-256 `0B:5D:13:99:F4:E0:1F:E2:A3:E1:5E:BF:40:B7:5B:69:32:2A:E0:2F:E9:8D:95:60:A9:3C:3C:9E:E2:7A:2E:D5`).

## First release: 0.5.0 (versionCode 12)

Built with `./gradlew :app:bundleEnterpriseRelease`; the release APK of the same build was
installed on the owner's phone on 2026-11-10 and passed against the lab Gateway
(`lab.gateway.veilo.tr`): enrolment with TLS pins, polling, number-matching sign-in
(ACCEPT in 30 s).

### Play Console steps (owner)

1. **Create app** → name "Veilo Enterprise", default language Turkish, App, Free.
2. Before the first release, **Setup → Advanced settings → Managed Google Play**: turn on
   *"Make this app private"* (it can be distributed only to the organisations you list; a
   private app can never be made public later — the public app stays the public one) and
   add the **organisation ID** of each customer's managed Google Play (Admin console of
   Google Workspace / Intune / the EMM shows it). For our own tests: the Test DPC account's
   organisation, or skip and use **internal testing** first.
3. **App signing**: accept Play App Signing; upload `app-enterprise-release.aab`. Play
   records the upload certificate above.
4. **Store listing**: short and full description below, icon and screenshots as for the
   public app (the Enterprise screens look the same plus the Gateway dialogs).
5. **App content**: privacy policy (same URL), Data safety — unlike the public app this
   edition **does** use the network: it sends the device's public key, attestation
   certificate chain, app version, OS patch level, device model and integrity flags to the
   organisation's own Gateway; nothing goes to Veilo. Data is not sold or shared; it is
   required for the app's function; encrypted in transit (TLS with pinning).
6. Release to the private track (or internal testing first), with the notes below.

### Release notes

**tr-TR**
> Veilo Enterprise'ın ilk sürümü. Kurumunuzun Veilo Gateway'ine bağlanır: QR ile kayıt,
> donanım anahtarı doğrulaması, telefonda sayı eşleştirmeli oturum onayı, kurum tarafından
> cihaz iptali. Gateway bağlantısı, QR'daki sertifika pinleriyle korunur.

**en-US**
> First release of Veilo Enterprise. Connects to your organisation's Veilo Gateway:
> QR enrolment, hardware key attestation, number-matching sign-in approval on the phone,
> device revocation by the organisation. The Gateway connection is protected by the
> certificate pins in the QR code.

### Listing text

Short (≤ 80): "Kurumsal MFA: Veilo Gateway ile sayı eşleştirmeli oturum onayı."

Full: start from the public app's description and add a paragraph: "Veilo Enterprise,
kurumunuzun kendi sunucusunda çalışan Veilo Gateway ile birlikte kullanılır. BT biriminiz
size bir QR kod verir; oturum açarken telefonunuzda çıkan sayıyı seçerek onaylarsınız.
Gizli anahtarlar telefonunuzun güvenli donanımından çıkmaz; Veilo'ya hiçbir veri gitmez."
