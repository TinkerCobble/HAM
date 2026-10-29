# Public HAM — Chat and Codex handoff

## Latest session — 2026-10-29, physical Pixel 8 code125 acceptance

- **Diagnosis/action:** the actual Pixel 8's public code124 APK reproduced the same permission-denied Calendar Provider observer crash as the emulator. The same-signer, higher-code public code125 APK installed over canonical HAM without uninstall or data clear.
- **Verification:** original first-install time retained; device-pulled APK SHA-256 `8FA775776360CE195CA232EECC41A2C5C84D50F35E7DF4253DCFEFFBC610467E` matched Note8/staged/live. HAM remained foreground after initial launch and force-stop/relaunch with no new crash. First-run screen has no connected HA or Android calendar source, so provider access is not claimed. No private log, endpoint, device identifier, calendar or credential entered this public repository.
- **Next:** owner may connect accounts through Pixel screens; continue provider/feature matrix and canonical update-feed work. Prior Pixel-pending entries are historical.

## Latest session — 2026-10-29, code125 source and live download

- **Repository/branch:** separate public `TinkerCobble/HAM` `main` source commit `ca56ce4`, pushed and remote-read back; private development history stayed private. A documentation commit follows this record.
- **Change:** canonical 0.3.9/code125 guards foreground Calendar Provider observation until Android calendars are enabled and current `READ_CALENDAR` permission exists, handling a revocation race. This fixes a code124 `SecurityException` reproduced on a clean Android 16 Pixel 8 emulator.
- **Verification:** clean public build/tests/lint, source privacy, Gitleaks current/history, exact source ZIP and signed APK scans, and GitHub privacy CI passed. Public/private unsigned APK members matched except generated VCS metadata. Signed code125 passed emulator no-permission/grant/revocation checks and Note8 same-signer in-place update with original first-install time and HA Connected state. Anonymous live APK SHA-256 `8FA775776360CE195CA232EECC41A2C5C84D50F35E7DF4253DCFEFFBC610467E`, source ZIP SHA-256 `AD8B46D66F631C217A2E35F9A833C8C67804930C2DE2A726D941ACA09B402BC6`; downloaded identity/signer checked.
- **Release/next:** website `main@bcc8474`, Pages preview `08214372`, public no-sign-in code125 download live. Real Pixel 8 in-place update and data acceptance await wireless ADB/device access. Canonical auto-update feed remains unpublished; old code124/121/DEV objects remain. Wider feature/provider gates stay in the private HAM matrix.

## Latest session — 2026-10-29, code125 Android 16 startup repair source

- **Cause/change:** exact public code124 reproduced a permission-denied startup crash on a clean Android 16 Pixel 8 emulator. Canonical 0.3.9/code125 guards Calendar Provider observer registration until `READ_CALENDAR` is held and Android calendars are enabled; no permission, account, storage or network path was added.
- **Source:** only `MainActivity.kt` and `app/build.gradle.kts` changed from the audited code124 app source; private development Git history and signing material remain separate. The public code124 website download is still live while this source candidate is audited.
- **Verification:** private clean JVM suite/lint/release build and focused foreground observer instrumentation passed; exact signed code125 hash is `8FA775776360CE195CA232EECC41A2C5C84D50F35E7DF4253DCFEFFBC610467E`. Clean Android 16 launch, grant and revocation/restart passed. Code125 installed in place on Note8 with original first-install time, old `.dev`, Connected HA state and exact device APK hash preserved. Public-source clean build, scan, source ZIP, website release and real Pixel 8 checks follow.

## Latest session — 2026-10-29, canonical code124 source publication

- **Repository/branch:** standalone public `TinkerCobble/HAM` `main` commit `a3958f9`, pushed and remote-read back. Private development Git history was not imported. The private HAM and website repositories were independently confirmed private before release.
- **Changed:** copied audited canonical 0.3.8/code124 app source and matching build files into this existing separate public repository; removed obsolete public DEV flavor source, kept historical release artifacts/tooling, and updated source/privacy documentation. Public unsigned release APK matched private candidate ZIP members except generated version-control metadata.
- **Verified:** clean release build, 156 JVM tests, debug lint, source privacy, Gitleaks current/history, exact source ZIP and signed APK scans passed. GitHub Actions privacy workflow on `a3958f9` completed successfully. Public source ZIP SHA-256 `F911124ACC6D096D3C0C5BB80F2F69C70ED10CA635F7154BC90AD274DCA7EEC9`; signed APK SHA-256 `00BA5D75CD4DB0FCEE4C37155910C5FC0D0451793078CF9F686EC289D8975276` matched anonymous live bytes.
- **Release:** source ZIP and signed APK are live from the HAM product page; website commit `a97b35f`, Pages preview `86cba304`. Pixel 8 and canonical update-feed acceptance remain open; broader feature/provider acceptance stays in the private matrix.
- **Recovery:** prechange complete public Git bundle is `Z:\TinkerCobble\HAM\pre-code124-public-source-20261029.bundle`; previous code121 public objects remain available. No real calendar data, account, token, private URL, device identifier, signing material or private Git history was included.

## Current session — 2026-10-29, canonical code124 source preparation

- **Repository:** `TinkerCobble/HAM` is public with separate history from private `DolinaJean/HAM`. The website repository `DolinaJean/TinkerCobbleWebsite` is private. No private Git history was merged here.
- **Source:** 0.3.8/code124 canonical app source and build configuration were copied as tracked files from the verified private candidate. Obsolete public DEV/Stable source flavors were removed; public-specific working rules and release/privacy tooling remain.
- **Verification:** a clean public unsigned release APK has the same size and all ZIP-member bytes as the private code124 unsigned candidate except build-generated version-control metadata. Public source privacy and Gitleaks current/history passed. The signed Note8 candidate SHA-256 is `00BA5D75CD4DB0FCEE4C37155910C5FC0D0451793078CF9F686EC289D8975276`; it stays outside Git.
- **Release:** publication and Pixel 8 installation are pending at this checkpoint. The existing public code121 APK and source stay available. Finish exact archive/artifact scans, public source push, R2/site deployment and live read-back before reporting code124 public.
- **Privacy:** no real account, server address, calendar record, token, device ID, private log, screenshot or signing material is part of this source snapshot. Runtime privacy review remains ongoing.

## Latest session — 2026-10-20, privacy CI and public-file audit

- **Repository and branch:** Public `TinkerCobble/HAM` `main`, based on code 13 source `24a8772bd253f5fe1800d6b0627f520bc3a49dbf`. Privacy repair commit `1a02fc1426c57de1592018650425f416bdd8ccfd` and checker refinement `8ad4c67b237a327c6b9d796e7b09f85a2881cab7` were pushed and remote-read back; later audit/handoff documentation commits are visible in `main` history.
- **What changed and why:** Replaced the incompatible Gitleaks Go install with a pinned, checksum-verified official binary. History, source, and published-artifact checks run independently. The data-flow document now accurately describes DEV updates, screenshot storage, address lookup, and Maps. See [audit](PRIVACY_AUDIT_20261020.md) and [release checklist](PRIVACY_RELEASE_CHECKLIST.md).
- **Validation:** Gitleaks 8.30.1 scanned all initially reachable eight public commits and current source with no findings. Source and current live-artifact checks passed locally. Private temporary synthetic fixtures proved clean and finding exit behavior without publishing the fixtures. [GitHub Actions run 39513781309](https://github.com/TinkerCobble/HAM/actions/runs/39513781309) passed all three jobs after the checker refinement. The separate website privacy correction was deployed and read back at the canonical site.
- **Release state:** Documentation and checks only; code 13 remains the installed/public DEV version. No APK, feed, Stable release, or phone installation changed.
- **Migrations and recovery:** None. Public commits have separate history from private development. The previous public commit remains available for a normal Git revert; release artifacts are unchanged.
- **Blockers and next actions:** Continue manual privacy review and exact staged APK/website preflight before any future release. Device network capture and private server storage were outside this audit. Future app promotion remains Note8 then Pixel 8 OTA.
