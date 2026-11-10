# Yönetici kılavuzu: Yönetilen cihazlarda Veilo Authenticator

Veilo Authenticator, MDM'iniz üzerinden dağıtılabilir ve güvenlik modelinde hiçbir değişiklik
olmadan kurum kurallarıyla yönetilebilir: uygulamanın hâlâ ağ izni yoktur ve kurallar ona
Android'in kendi yönetilen yapılandırma kanalıyla ulaşır. Aynı APK hem kişisel hem yönetilen
cihazlarda çalışır; yapılandırması olmayan bir cihaz tam olarak herkese açık sürüm gibi davranır.

Android 11 veya üstü ve Android Enterprise'a kayıtlı bir cihaz gerekir; tam yönetilen cihaz
ya da iş profili olabilir.

## 1. Uygulamayı Managed Google Play'de onaylayın

1. EMM konsolunuzda (Microsoft Intune, Google Workspace, Samsung Knox Manage, VMware
   Workspace ONE ve diğerleri) Managed Google Play mağazasını açın.
2. **Veilo Authenticator** uygulamasını (paket `tr.veilo.authenticator`) arayın ve onaylayın.
3. İhtiyacı olan kullanıcı gruplarına veya cihazlara atayın. Kurulum zorunlu yapılabilir.

## 2. Yönetilen yapılandırmayı ayarlayın

Her EMM aynı formu gösterir; form uygulamanın içindeki şemadan üretilir:

| Ayar | Varsayılan | Değiştirildiğinde etkisi |
|---|---|---|
| Kurum adı | — | Uygulama başlığının altında ve Hakkında ekranında gösterilir |
| Destek iletişimi | — | E-posta veya telefon; Hakkında ekranında ve engelleme ekranında gösterilir |
| Yalnızca güvenlik çipi | kapalı | Yeni ve içe aktarılan hesaplar, cihazın güvenlik donanımında dışa aktarılamaz anahtar olarak saklanır. Dışa aktarılabilir mevcut hesaplar, hiçbiri kalmayana kadar her kilit açılışında bir kez "güvenlik çipine taşı" uyarısı alır. |
| Şifreli yedek dosyalarına izin ver | açık | Kapatılırsa parola korumalı yedek dışa aktarma gizlenir |
| Yedek içe aktarmaya ve diğer uygulamalardan taşımaya izin ver | açık | Kapatılırsa yedek içe aktarma ile Google, Microsoft veya Aegis'ten taşıma gizlenir |
| Yazdırılabilir PDF yedeğe izin ver | açık | Kapatılırsa PDF dışa aktarma gizlenir |
| Hesapları başka telefona göndermeye izin ver | açık | Kapalıysa: bu telefon cihazdan cihaza aktarımda gönderen olamaz |
| Başka telefondan hesap almaya izin ver | açık | Kapalıysa: bu telefon alıcı olamaz |
| Root'lu cihazlarda kodları engelle | kapalı | Açıksa: uyarı bandı yerine, root'lu veya değiştirilmiş cihazda kodlar hiç gösterilmez |
| Debugger bağlıyken kodları engelle | kapalı | Açıksa: debugger veya izleyici bağlıyken kodlar gösterilmez |

Formu nerede bulursunuz:

- **Intune:** Uygulamalar → Uygulama yapılandırma ilkeleri → Ekle → Yönetilen cihazlar →
  platform Android Enterprise → uygulamayı seçin → "Yapılandırma tasarımcısını kullan".
- **Google Workspace:** Cihazlar → Mobil ve uç noktalar → Uygulamalar → Web ve mobil
  uygulamalar → uygulama → Yönetilen yapılandırmalar.
- **Knox Manage:** Uygulama → uygulama → Yapılandırmayı ayarla.

Değişiklikler dakikalar içinde cihaza ulaşır; uygulama bunları bir sonraki kilit açılışında,
açıksa hemen uygular.

## 3. Kullanıcı ne görür

- Uygulama başlığının altında "<kurum> tarafından yönetiliyor".
- Kapattığınız her şey için gizlenen menü öğeleri; bu ekranlara başka hiçbir yoldan da ulaşılamaz.
- "Yalnızca güvenlik çipi" açıkken: hesap ekleme ekranında depolama seçimi kalkar ve mevcut
  dışa aktarılabilir hesapları çipe taşımayı öneren bir pencere çıkar. Kodlar taşıma sırasında
  ve sonrasında çalışmaya devam eder.
- Hakkında → "Kurum politikası", yürürlükteki kuralları ve destek iletişiminizi listeler.
- Engelleyici bir kural ve eşleşen bir risk varsa: hesap listesi yerine destek iletişiminizi
  gösteren tam ekran bir bildirim.

## 4. Bilinmesi gereken sınırlamalar

- **Güvenlik çipi için fazla kısa anahtarlar.** Android Keystore, 64 bitten kısa HMAC
  anahtarlarını reddeder. Bazı servisler böyle anahtarlar verir. "Yalnızca güvenlik çipi"
  açıkken bu hesaplar eklenemez; kullanıcıya servisten yeni anahtar istemesi veya sizinle
  iletişime geçmesi söylenir. Çipe taşıma penceresi bunları yeniden kayıt gerektiren hesaplar
  olarak bildirir.
- **Sabit kilit ayarları.** Kimlik doğrulama her 2 dakikalık anahtar kullanımında istenir ve
  biyometrik ya da cihaz kimlik bilgisi olabilir. Bunlar anahtarların kendi özellikleridir,
  yapılandırmayla değiştirilemez.
- **Raporlama yok.** Uygulama çevrimdışı olduğundan bir kuralın yürürlükte olduğunu
  konsolunuza bildiremez. Bir test cihazında doğrulayın. Uzaktan attestation ve raporlama,
  bağlı kurumsal sürümle ve Veilo MFA Gateway ile gelir.
- **Root tespiti sezgiseldir.** Sıradan root kurulumlarını yakalar; gelişmiş bir gizleme aracı
  atlatabilir. Sağlam çözüm donanım attestation'ıdır (bağlı sürüm).

## 5. EMM aboneliği olmadan test

Google'ın **Test DPC** uygulaması (Play Store), geliştirme için cihaz politikası denetleyicisi
görevi görür:

1. Test DPC'yi kurun; bir test telefonunda iş profili oluşturun veya cihaz sahibi olarak ayarlayın.
2. Veilo Authenticator'ı Play'den bu profile kurun.
3. Test DPC'de: Uygulama kısıtlamalarını yönet → uygulamayı seçin → değerleri ayarlayın → Kaydet.
4. Uygulamayı açın; "… tarafından yönetiliyor" yazar ve kuralları uygular.
