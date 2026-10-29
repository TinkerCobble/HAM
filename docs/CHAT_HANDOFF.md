# Public HAM — Chat and Codex handoff

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
