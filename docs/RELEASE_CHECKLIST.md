# HAM release checklist

Use a new checklist copy for each distributed version. A candidate is not a
release until every applicable gate has evidence. Keep DEV and Stable records
separate.

1. Record the exact private tested source commit, audited public source commit,
   and immutable public release tag. Verify production files match. Record the
   package ID, flavor, versionName, monotonically increasing versionCode,
   minimum Android version, and release notes/changelog for this binary.
2. Run tests, lint, privacy scan, dependency/license review, and a clean
   release build. Record APK SHA-256, size, signer SHA-256, and manifest
   permissions. Verify no private data or signing material entered the source.
3. For Stable, test the candidate on Note8, including Home Assistant reads and
   available write acceptance; back up Pixel 8, then update in place and check
   connection/data retention. Stop promotion on failure. Keep previous signed
   APK and source commit for recovery; Android downgrade may require a higher
   code signed with the same certificate.
4. For F-Droid, run the exact fdroiddata recipe at the full public commit.
   Compare its unsigned rebuild to the versioned developer-signed reference
   using F-Droid's reproducible verification, record the result and allowed
   signer fingerprint. Verify installer-specific update routing. Do not infer
   equivalence from matching package names or two compiling builds.
5. Publish only after explicit public Stable approval: versioned signed APK,
   checksum, corresponding GPL source/tag, website text and release notes,
   then live-read-back of each. Publish the direct Stable update manifest last.
   Check its package/version/code/URL/hash against the live artifact. Record
   GitHub release information and matching Fastlane changelog (`<code>.txt`).
6. Submit fdroiddata metadata only after its build and privacy/screenshots
   pass. Record review response and public listing URL if accepted; add a
   website F-Droid badge only then. Archive hashes, evidence, and Z: recovery.

For DEV, retain its separate website/update feed and never present an
unaccepted Stable candidate as a completed release.
