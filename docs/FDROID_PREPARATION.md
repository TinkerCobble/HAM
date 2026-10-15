# F-Droid preparation (not submitted)

## Candidate identity and distribution

The proposed main listing is **Home Agenda Manager**, package
`com.tinkercobble.ham`, flavor `stableFdroid`. The current source declares
versionName `0.1.0`, versionCode `1`; these are *candidate values*, not a
distributed release or accepted tag. `v0.1.0` is a proposed Stable tag name,
not a created tag. The public DEV app is
`com.tinkercobble.ham.dev`, version `0.1.10-dev`/code `11`, and is separate.

`stableFdroid` has no direct APK installer permission/provider and tells users
to check F-Droid. `stableDirect` checks the TinkerCobble Stable feed and can
offer a verified APK after user approval. Their updater code and manifests
differ. Therefore the currently prepared flavor APKs are not byte-identical;
do not assume a website `stableDirect` APK can be used as the reference binary
for an F-Droid `stableFdroid` build. Both share the Stable package ID, so
signing identity must be settled before either is distributed.

Preferred route: sign both Stable flavors with **one protected permanent Stable
certificate**, using a versioned `stableFdroid` APK as F-Droid's reproducible
reference and a separate versioned `stableDirect` APK for website downloads.
F-Droid's `Binaries` and `AllowedAPKSigningKeys` fields would verify the exact
developer-signed F-Droid reference. The two flavor binaries need not be
byte-identical to each other; each must match its own declared package,
version, and signer, while the F-Droid rebuild must match its F-Droid reference
apart from signature. The common signer and increasing shared versionCode can
allow Android in-place cross-distribution updates, but installer-source routing
and data retention must be tested in both directions before claiming that
compatibility. A user who sideloads one flavor may change their update route.
If F-Droid must sign with its own certificate instead, cross-channel in-place
updates are incompatible. Explain that before the first release. Do not reuse
the DEV certificate for Stable or assume Play's certificate matches.

No permanent Stable signer, developer-signed Stable reference APK, exact public
release commit/tag, or F-Droid buildserver result exists yet. The metadata
template under `fdroid/` is intentionally disabled; it must not be submitted
with placeholders or enabled until the exact source commit, build recipe,
reference APK URL, and signer fingerprint are verified. F-Droid's official
reproducible-build process compares a rebuild with the signed reference APK,
including APK bytes and signature compatibility. Two successful local Gradle
builds alone do not establish that result.

## Privacy-safe screenshots

The upstream Fastlane listing text and icon are in `fastlane/metadata/android/en-US/`.
Six screenshots are in its `images/phoneScreenshots/` directory: Today, Month,
next 7 days, 3 days, calendar selection, and Settings. They were captured from
the actual Compose interface on a disposable Android 36 emulator with the
opt-in `app/src/debug/.../DemoFixture.kt`; it supplies only synthetic events,
calendar IDs, and a reserved `.invalid` server address. The fixture exists
only in debug builds, and its entry point requires an explicit intent extra.
No personal Home Assistant connection or demo calendar write was used. Inspect
each screenshot and PNG metadata again before external submission; the debug
Settings screen explicitly identifies its synthetic preview state.

## Supported behavior and unaccepted checks

See `docs/ARCHITECTURE.md` and current source for implementation details.
Calendar and open-task data are fetched from Home Assistant into memory.
There is no persistent offline event cache or queued write. The most recently
loaded data may remain visible in the running process after a refresh error,
with a failure/retry indicator, but does not become a guaranteed offline copy.
Refresh asks Home Assistant again; providers can lag or reject an operation.
HAM cannot force an upstream provider to synchronize. Calendar creation is
all-day Quick Add on a currently writable calendar selected by exact entity
ID. Editing is title-only when the calendar reports update capability and the
event has a stable UID and no recurrence instance. Read-only/unavailable
calendars cannot be selected for create. Deletion, recurrence editing, task
editing, and general provider management are unsupported.

Real provider create/read/delete and recently-used persistence remain untested
because there is no dedicated disposable test calendar. Stable-to-Stable
in-place data retention, Note8 and Pixel 8 acceptance of a Stable candidate,
token refresh, remote connectivity, cross-channel updates, reproducible
F-Droid verification, screenshot review, and F-Droid scanner/inclusion review
remain gates. Automated tests or local builds do not satisfy those gates.

## Release recipe

Follow `docs/RELEASE_CHECKLIST.md`. The actual fdroiddata build entry must
point to the exact full public source commit, use `gradle: stableFdroid` only
if that flavor remains the chosen artifact, and contain a tested versioned
`Binaries` URL and `AllowedAPKSigningKeys` fingerprint only after reference
binary equivalence succeeds. The reference URL must address the immutable
developer-signed `stableFdroid` APK, not the Direct flavor. Leave automatic update checks disabled until a
real tag/version convention is tested. Do not display an F-Droid availability
badge before the listing exists.

## Local preparation checks — October 14, 2026

The current source built `stableFdroidRelease` as an unsigned 1,959,333-byte
APK (`com.tinkercobble.ham`, `0.1.0`/code `1`, minSdk 26). The resulting local
SHA-256 is `F678D1E5B578277C85C03E79A4946C0088388F5E405728CC802BE9EFE929D87D`.
`apksigner verify` rejected it as unsigned, as expected. An earlier independent
two-worktree build of source `84f6d9f` produced identical unsigned APK hashes
`E9C9E8896E80FDBD14A736CF550C67FB1B36FDE263767F8ADDD41B106F16BB80`;
that source predates the opt-in screenshot fixture and is not a signed
reference proof. A second clean worktree built private preparation source
commit `1caddb1b601636845b053544b6bbc5a9901fc35d` with the same
`:app:assembleStableFdroidRelease --no-daemon` recipe and reproduced the
current unsigned SHA-256 exactly. This is local reproducibility only; an
F-Droid buildserver verification remains open. The draft YAML parsed and
`fdroidserver` 2.4.5 `fdroid lint com.tinkercobble.ham` passed after checking
`Calendar & Agenda` against the current official fdroiddata category file.
The local Windows CLI warned that it could not find `apksigner`; this lint
run did not verify an APK signature. F-Droid scanner and buildserver checks
have not passed because there is no enabled signed Stable build. The disabled recipe has no
`Builds` entry and cannot trigger an upload.
The same unsigned hash was built from the audited public preparation branch
`97807a638b584c182d8461526481626af92306a0`. The public main commit
`0f6ed9e9bfc37cefa3c49fdb215bf44061f5355c` remains the source matching
the current released DEV APK, rather than this new preparation source.

Stable F-Droid debug/release builds, F-Droid unit tests and lint, DEV/Direct
unit tests, DEV/Direct/Play debug builds, and the public source privacy check
passed. `aapt` showed the install-packages permission in DEV/Direct only.
Inspection of release DEX found no `DemoFixture`, `demo.invalid`, or debug
intent marker. Six 1080×2340 emulator PNGs were visually reviewed and contain
only synthetic labels; their PNG metadata contains only the sRGB marker.

Official references: [submission](https://fdroid.gitlab.io/jekyll-fdroid/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [build metadata](https://fdroid.gitlab.io/jekyll-fdroid/docs/Build_Metadata_Reference/), [reproducible builds](https://fdroid.gitlab.io/jekyll-fdroid/docs/Reproducible_Builds/), [inclusion policy](https://fdroid.gitlab.io/jekyll-fdroid/en/docs/Inclusion_Policy/).
