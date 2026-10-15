# HAM changelog

This file records published experimental DEV versions. Stable has no released
version or F-Droid changelog yet. Each future distributed version must use
`docs/RELEASE_CHECKLIST.md` and a matching immutable artifact/source record.

## 0.1.10-dev (versionCode 11) — 2026-10-14

- Added user-approved in-app updates for direct DEV installations, with
  checksum, package, version, and signer checks before Android installation.
- Kept the calendar/navigation work from 0.1.9-dev. Tested an in-app update on
  Note8 and an in-place update on Pixel 8; both retained Home Assistant access.

## 0.1.9-dev (versionCode 10) — 2026-10-14

- Added Today, Month, next 7 days, and 3-day calendar navigation, calendar
  visibility/color preferences, search, and improved refresh/error handling.
- Tested an in-place Note8 update. Calendar create/read/delete acceptance still
  needs a dedicated disposable test calendar.

## 0.1.8-dev (versionCode 9) — 2026-10-13

- Established the permanent Android 9+ DEV signing identity and published the
  first accepted experimental DEV APK with matching GPL source.
- Tested an in-place Note8 update and read back its Home Assistant connection.
