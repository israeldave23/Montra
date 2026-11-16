# Releasing Montra

Everything that stands between this repository and a Play upload, in the order it has to happen. Each
section says what is already wired and what needs a human with a signing key and a Play account.

## 1. Signing

The build reads `keystore.properties` from the repository root **if it exists**, and produces an
unsigned release build if it does not — which is exactly what CI uses to exercise R8 on every push.

Generate a key once and keep it somewhere that is not this repository:

```bash
keytool -genkeypair -v -keystore montra-release.jks -alias montra \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keystore.properties` (git-ignored; `keystore.properties.example` is the template):

```properties
storeFile=../montra-release.jks     # relative to the app/ module
storePassword=…
keyAlias=montra
keyPassword=…
```

**Losing this key means losing the ability to update the app** under the same listing. Play App
Signing, with an upload key that can be reset, is the safer arrangement if you expect to lose a file.

CI: the workflow does not sign. Add the keystore as base64 in a secret and write it in a job when you
want CI-built signed artifacts:

```yaml
- run: echo "$KEYSTORE_BASE64" | base64 -d > montra-release.jks
- run: printf 'storeFile=../montra-release.jks\nstorePassword=%s\nkeyAlias=montra\nkeyPassword=%s\n' \
    "$STORE_PASSWORD" "$KEY_PASSWORD" > keystore.properties
```

## 2. R8 and resource shrinking

`isMinifyEnabled = true` and `isShrinkResources = true` for release. CI runs `assembleRelease`
unsigned on every push, so a keep rule that stops being enough is a red build rather than a crash in
a shipped APK, and `mapping.txt` is uploaded as an artifact — **keep it**: without the matching
mapping a release stack trace cannot be de-obfuscated.

`app/proguard-rules.pro` holds the small set of rules Play Services' and Room's consumer rules cannot
know about:

| Rule | Why |
| --- | --- |
| `-keepattributes SourceFile,LineNumberTable` | Without it every release stack trace is line 0 |
| `-renamesourcefileattribute SourceFile` | Keeps file names out of the trace |
| `-keep class com.dga.montra.db.** { *; }` | Room loads `FinanceDatabase_Impl` by name and reflects over entities |
| `-keep class com.google.android.gms.ads.MobileAds` | Entry point the manifest and our `Ads` object reach by name |
| `-keep class com.google.ump.**` | Consent forms are inflated by class name |
| `-keepclassmembers class * extends android.content.ContentProvider` | `FileProvider` is instantiated by the framework |

If R8 reports `Missing class …` for an optional SDK class, the fix is a narrow `-dontwarn` for that
package — never `-dontoptimize`, and never a keep for a whole SDK.

## 3. `app-ads.txt`

The file is in the repository root:

```
google.com, pub-5236559514557783, DIRECT, f08c47fec0942fa0
```

It has to be **served over HTTPS from the developer website on the Play listing**, at the domain root:

```
https://<your-domain>/app-ads.txt
```

The Play listing's developer website must be that same domain, and the file must be plain text with
no redirects. AdMob reports its status under *Sites → app-ads.txt*; verification takes up to 24 hours
and is required for demand from buyers who check it. `google.com` covers AdMob's own demand; keep the
line until the account stops serving through Google.

## 4. Play Console answers

These must match the code. Re-read `ADS.md` and `data/AdPrefs.kt` before changing them.

### Data safety

| Question | Answer | Why |
| --- | --- | --- |
| Does the app collect or share data? | **Yes** | The AdMob SDK does, even though Montra itself stores everything on the device |
| Data types collected | Device or other IDs (advertising ID), approximate location (derived from IP, by the ad SDK), app interactions (ad impressions) | All from the ads SDK |
| Purpose | Advertising or marketing; fraud prevention | Not analytics: there is no analytics SDK |
| Is data encrypted in transit? | **Yes** | The SDK uses HTTPS |
| Can users request deletion? | **No account exists**; uninstalling removes everything Montra stored | Say so in the listing |
| Is data shared with third parties? | **Yes** — Google AdMob | Required for ads |
| Financial info | **Not collected** | Accounts, transactions and goals never leave the device |

The app's own data — accounts, transactions, budgets, goals, profile photo — is stored in a local
Room database and is never uploaded. `android:allowBackup="false"` keeps it out of Google's backup,
and the only ways out are the export, backup and share actions the user starts.

### Content rating and audience

- **Not designed for children.** No content that targets under-13, and ad content is not
  child-directed; answer the families questions accordingly.
- Ads contain a **consent gate** for EEA/UK users via Google's UMP SDK, plus a "privacy options" row
  in the profile that reappears when consent needs renewing.
- Contains ads: **Yes**.

### Other declarations

- **Ads**: yes, AdMob. Declaration must be checked or the app is rejected.
- **App access**: no login, so "all functionality is available without special access".
- **Government apps / financial features**: Montra is a personal ledger, not a bank, payment or
  lending product. It does not move money. Do not select financial-services declarations.
- **Target API level**: 35, above Play's current floor.

## 5. Version and build

```kotlin
versionCode = 1      // must increase for every upload, forever
versionName = "1.0"
```

Set both in `app/build.gradle.kts` and mirror them in `app/module.toml`; CI builds what the file says.
A bundle, not an APK, is what Play wants:

```bash
gradle :app:bundleRelease        # app/build/outputs/bundle/release/app-release.aab
gradle :app:assembleRelease      # APK, for sideloading and the R8 check
gradle :app:testDebugUnitTest    # 56 tests
```

## 6. Before the first upload

- [ ] Keystore created, `keystore.properties` filled in locally, **not** committed
- [ ] `gradle :app:bundleRelease` succeeds with `minifyEnabled` on
- [ ] The `.aab` installs from a clean device and the app opens (R8 is the usual suspect here)
- [ ] Ads load on a device with the real unit IDs; consent form appears for an EEA VPN
- [ ] Backup → restore round trip on a device: transactions added just before the backup are there
      after the restore, and the app comes back on its own
- [ ] `app-ads.txt` served and verified in AdMob
- [ ] Screenshots, feature graphic, description
- [ ] Data-safety answers match section 4
- [ ] `mapping.txt` archived for the version being uploaded
