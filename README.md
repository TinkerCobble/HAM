# HAM — Home Agenda Manager

HAM is a native Android agenda for calendars the user chooses to connect. Home Assistant and each calendar provider own their records. HAM has no TinkerCobble account, ads, analytics, AI scheduler, or hosted calendar backend.

## Public release

HAM 0.3.8 (versionCode 124) is the current canonical Android package, `com.tinkercobble.ham`. The exact signed APK is distributed at [TinkerCobble's HAM page](https://tinkercobble.com/ham/) with an immutable matching source archive. This public repository contains the audited standalone source snapshot; private development Git history and signing material are not included. The older `.dev` package is separate and remains available only for migration compatibility.

Code124 was installed over the existing canonical app on a Note8 with the established signer and its data preserved. Disposable iCloud tests verified timed event location and description edits, server read-back, and one stale-version conflict. HAM remains in active development; see [status](docs/STATUS.md) and the public release notes. A public download does not claim full provider or feature acceptance.

## Build

Install JDK 21 and Android SDK platform 37. This source uses the Gradle 9.8.0 wrapper. From the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
python scripts/privacy_check.py
```

The release task produces an **unsigned** APK. The signed production APK uses protected signing material outside this repository; a local debug or unsigned build cannot update an installed production app. This source snapshot's clean release APK matched the private candidate in all package entries except build-generated version-control metadata. The signed APK itself is not stored in Git.

## Accounts and data

HAM connects directly to the user's Home Assistant and supported calendar providers. Calendar choices and protected credentials stay in Android app-private storage. Calendar writes are offered only where the selected provider reports the required capability. Offline changes can remain queued for review; provider records are authoritative. See the [privacy data flow](docs/PRIVACY_DATA_FLOW.md) and the [website privacy policy](https://tinkercobble.com/ham/privacy/).

## License and notices

HAM-authored code and project-owned icon artwork are [GPL-3.0-only](LICENSE). Third-party components retain their licenses; see [notices](THIRD_PARTY_NOTICES.md) and [OCR license materials](third_party_licenses/tesseract4android-4.9.0/). Report security issues privately to [support@tinkercobble.com](mailto:support@tinkercobble.com); keep credentials, private calendar data, and screenshots out of public issues.
