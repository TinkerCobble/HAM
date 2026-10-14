# HAM — Home Agenda Manager

HAM is a native Android app for an existing [Home Assistant](https://www.home-assistant.io/) installation. It reads calendars and open to-do items, and can create events or edit event titles when the selected calendar reports support. Home Assistant and its configured providers remain the source of truth; HAM does not host calendar data.

This repository begins with the complete source snapshot for the **experimental HAM DEV 0.1.8-dev (versionCode 9)** build tested on a Samsung Note8 and distributed from [TinkerCobble's HAM page](https://tinkercobble.com/ham/). It has fresh public Git history; private development history was not copied here. DEV uses `com.tinkercobble.ham.dev` and requires Android 9 or newer. Stable uses a separate package identity and has not been released. No APK or signing key is stored in this repository.

## Build

Install JDK 21 and Android SDK platform 37, with an Android SDK accepted by the Android Gradle Plugin. This source was built with Android Studio's JDK 21 runtime and the Gradle 9.8.0 wrapper. From the repository root, run:

```powershell
.\gradlew.bat :app:assembleDevDebug :app:testDevDebugUnitTest :app:lintDevDebug
python scripts/privacy_check.py
```

On macOS or Linux, use `./gradlew` and `python3`. The local debug APK is produced at `app/build/outputs/apk/dev/debug/app-dev-debug.apk`. It is signed with your local Android debug identity and **cannot update** the publicly distributed DEV app, which uses a separate permanent signer. Never uninstall the installed DEV app merely to test a locally built APK if its data matters.

The `stableDirect`, `stablePlay`, and `stableFdroid` flavors share this source tree. They are development preparations; a build from them is not a released Stable product. Release APK signing uses private credentials that are deliberately absent. This source does not contain a Stable APK or update manifest.

## Home Assistant connection

In HAM DEV, open Settings, enter the address of a Home Assistant installation you control, then sign in on Home Assistant's authorization screen. HAM uses `https://tinkercobble.com/ham/` as its OAuth application identity. The authorization callback stays in the app. The public page does not receive your authorization code or calendar contents. Tokens are stored in Android private encrypted storage; loaded calendar and task information is held in memory.

Calendar choices are keyed by the exact Home Assistant entity ID, including when names are identical. A calendar must report write support before HAM offers creation. There is no automatically selected first writable calendar; a user can choose an explicit default. Tasks are read-only in this source snapshot.

## Status and distribution

The current public download is labeled **Experimental DEV build** on the [HAM product page](https://tinkercobble.com/ham/). It installs separately from a future Stable app. The release path for Stable is Note8 acceptance, then a data-preserving Pixel 8 acceptance, then an explicitly approved public release. There is no Stable APK, Google Play listing, or F-Droid submission. The F-Droid flavor is preparatory and uses that distribution channel's normal update route; eligibility and build verification remain open.

## License and notices

HAM-authored code and project-owned icon artwork, including `design/ham-approved-original.png` and generated variants, are offered under [GNU GPL v3 only](LICENSE) (`GPL-3.0-only`). Third-party dependencies retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md) and [artwork/dependency notes](docs/OPEN_SOURCE.md). The original icon was created by the project owner using AI tools and approved for this license. No third-party artwork source was identified.

See the public [Privacy Policy](https://tinkercobble.com/ham/privacy/) and [Terms](https://tinkercobble.com/ham/terms/). For support or privacy questions, use [support@tinkercobble.com](mailto:support@tinkercobble.com).
