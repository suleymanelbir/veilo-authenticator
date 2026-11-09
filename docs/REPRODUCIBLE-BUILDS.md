# Reproducible builds

Anyone can check that the APK on Google Play was built from this repository, with nothing
added. The build is deterministic: the same source, built with the pinned toolchain, gives
the same bytes every time. Only the signature differs, and that is stripped before comparing.

## What makes the build deterministic

- Toolchain versions are pinned: Gradle in `gradle/wrapper/gradle-wrapper.properties`,
  Android Gradle Plugin and Kotlin in `gradle/libs.versions.toml`, JDK 17.
- `dependenciesInfo` is disabled in `app/build.gradle.kts`, so Google's encrypted dependency
  blob (which differs per build and cannot be reproduced) is not embedded.
- No build step reads the clock, the host name or the environment into the artifact. ZIP
  entry timestamps are normalised by the Android Gradle Plugin.
- R8 and resource shrinking are deterministic for a fixed input and version.

## How to verify

1. Install JDK 17 and the Android SDK (command-line tools are enough; `sdkmanager` will fetch
   the pinned platform and build-tools on the first build).
2. Download the APK you want to check. On a phone with the app installed:

   ```
   adb shell pm path tr.veilo.authenticator
   adb pull /data/app/.../base.apk published.apk
   ```

   An APK extracted from a Play App Bundle is a *split* configuration; compare against the
   universal APK in the GitHub release for that version instead, or build an AAB and compare
   `base/` entries.
3. Clone the tag for that version and run:

   ```
   git clone https://github.com/suleymanelbir/veilo-authenticator
   cd veilo-authenticator
   git checkout v0.3.0
   scripts/reproducible-build.sh published.apk
   ```

The script builds from clean, extracts both APKs, drops any signature files and compares a
SHA-256 manifest of every entry. It prints `MATCH` or lists the differing entries.

Run it with no argument to build twice and check that this machine produces identical output.

## Verification log

| Version | Date | Result |
|---|---|---|
| 0.3.0 (versionCode 10) | 2026-11-09 | Two builds from clean on the release machine, the second with the Gradle build cache disabled, produced byte-identical APKs: `d86abfa3500e158911c1b4cfce40b34c8bc21e5201bf9cb6aa7d246f109d06d3`. The signature was identical too, since APK Signature Scheme v2 with the same key is deterministic. |

## Known limits

- The check covers the APK that the build produces. It does not cover the Play Store's own
  re-signing or any split APKs Play derives from the bundle; compare against the universal
  APK published in the GitHub release.
- A different JDK major version, or a different AGP/Kotlin version, changes the bytecode and
  will not match. Use the pinned versions.
