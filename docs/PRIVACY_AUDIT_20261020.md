# Public HAM privacy audit — 2026-10-20

This is a sanitized record. Findings with private values belong in a private incident channel, never in public CI logs or this repository.

## Coverage and results

- Public Git repository: `TinkerCobble/HAM`. Before this repair, `main` was `24a8772bd253f5fe1800d6b0627f520bc3a49dbf`; the other reachable branch tip was `541b715b5c2155a42d551ebd20ed1a7ec3cfabc8`. There were eight reachable commits and no tags. Gitleaks 8.30.1 scanned all eight commits and the current source tree with redacted output: **no secret finding**. Commit author/committer metadata used the public TinkerCobble noreply identity. File paths and first-party source, tests, fixtures, configuration, and documentation were reviewed for private literals and unexpected files.
- The six screenshots reachable on the public F-Droid preparation branch were visually reviewed. They show synthetic demo calendars/events and no account connection. The screenshots and project PNG artwork had no EXIF fields; the screenshots carried only sRGB metadata. No personal screenshot was identified in inspected public Git objects.
- Live downloadable DEV APKs and source ZIPs were independently fetched and inspected as ZIP containers, with filenames, entries, image metadata, and selected secret/private-data patterns checked. Their SHA-256 hashes were verified against the website files and are recorded below. No candidate private file, owner network address, user-profile path, or secret pattern was found. Current code 13 APK and source ZIP matched their pinned checksums. The code 13 source ZIP's text files matched the public code 13 source revision line for line; line endings differed in some files.
- Live HAM product, privacy, terms, stylesheet, checksum files, and update feed were fetched and checked. The local website source revision compared was `719066e712fa8a2ab4e071159be96ca8695e61fa`; live product/legal content matched it apart from HTML comments around the public contact link. The feed pointed to the byte-verified code 13 APK. The published policy correctly describes on-device OCR, optional address query, private Home Assistant attachments, DEV update requests, and the absence of an analytics SDK.
- Source review found that the address lookup constructs its HTTPS request from the venue and address fields only. Screenshot bytes and unrelated calendar data are handled in separate paths. Tapping Maps hands the address to the user's chosen map app, which may use its network. The session and unsaved draft text use Android Keystore-backed AES-GCM; the image file itself is app-private but not HAM-encrypted. The Home Assistant attachment endpoint requires authentication and stores image bytes privately on the owner's server, without HAM-side encryption. Android backup is disabled. HTTP to a private Home Assistant address remains possible after a warning. The public data-flow document and website policy were corrected to state these boundaries.

## Exact live download hashes

| Public file | SHA-256 |
| --- | --- |
| `ham-0.1.8-dev-code9.apk` | `8d34c87f7c3a373a8ca64b6ab6ab21fa2d63ee7e7779eb4e7b7c7b74b525e320` |
| `ham-0.1.8-dev-code9-source.zip` | `595e5c50211b08686619e5b4f2ea0f37b35640dccddd43888bc07d5b22ce0036` |
| `ham-0.1.9-dev-code10.apk` | `30d49d4e0c817350cde8f81035a8235718266d2d1e43915fc4d252930f4b7d32` |
| `ham-0.1.9-dev-code10-source.zip` | `4d685b4643f45011fe2c0c92d419b5976067ee937b45359c6e23ac74dad61d8f` |
| `ham-0.1.10-dev-code11.apk` | `5e790aea32117b4ba11674aea865be2ef4e207803bc8a79c3b9f3f34b5cbbc49` |
| `ham-0.1.10-dev-code11-source.zip` | `86974007a61c40959dcfbf259ea9cd00fd68c0a03fb2dbba85a22f6c307bf1657` |
| `ham-0.1.12-dev-code13.apk` | `8387aa30d07d7822f6a701fc1557a69712b8784c6fbe3e5ffaab9dc781ec58cd` |
| `ham-0.1.12-dev-code13-source.zip` | `521b01506da4d283398512f7f1eb89a607632bc13badad0c54ebc4251f14c2d3` |

Live HAM page SHA-256: product `6f9fac976107589203ce58fa51cf17f196c213296e32c48ed6927b544e9ed2dc`; privacy `8ce4191b6168f7ede7f7eb8adcd3c9eb95ea6f79fe65ca575c5764cde7920408`; terms `cd402d4b3250572d1bd900e373e30f9e9fb2cb7b8cbb1916b7cd468f9bf082e2`; DEV feed `07e0631a4f938dbb4e4d9e464ab09dc27afe736833eee4120c45d1abd8a0b04c`; stylesheet `0ce12357040ef7db201d18ee3c6fb544d143921460d0dc33d675e049df09b1c9`; APK checksum file `78fdef32265927bab86d83b2280c497755d78a7c44fbb0210a776a8d849f0d0b`; source checksum file `54ee1d082701299bbe6bdabd2c7706c546a87c0ff14c193fc7d70fb11ac3358a`.

## Limits

Static pattern scans can miss encoded, compressed, novel, or contextual personal information. Visual screenshot and source review were performed, but there was no live traffic capture from an installed owner device and no audit of a private Home Assistant database, Android private app storage, or Cloudflare/R2 request logs. The review did not inspect Git objects unreachable from public branches/tags or other repositories. The live website was fetched as an anonymous visitor; server-side Cloudflare/R2 configuration was not changed. Synthetic isolated gate tests demonstrate detection behavior, not every possible privacy regression. No app version, APK, phone installation, website deployment, or feed changed during this repair.
