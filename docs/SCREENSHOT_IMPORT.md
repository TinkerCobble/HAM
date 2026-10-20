# Screenshot to calendar (DEV candidate)

HAM can receive an image from Android Share or the system photo picker. It copies the image into private app storage and runs bundled English Tesseract OCR on the device. Extraction suggests a title, one or more dates, a time range, venue, and address; it never saves an event without review. Missing years or times remain blank or warned about. The full recognized text remains editable in the description so uncertain details are visible.

The user reviews one event date at a time, explicitly selects a currently writable Home Assistant calendar by entity ID, and can optionally tap **Verify address** to query OpenStreetMap Nominatim. The image is never sent to Nominatim. A screenshot is uploaded only when the user taps **Save Event**. HAM compares the returned SHA-256 and authenticated image read-back with the local image before creating the event. It rechecks calendar write capability and existing events immediately before writing, includes a stable attachment marker in the event description, and reads the saved event back. A retry searches for that marker to avoid a duplicate after an uncertain network response. Similar title/date events prompt for explicit confirmation.

The optional `ham_attachments` Home Assistant integration provides authenticated upload, read, and delete under `/api/ham_attachments`. Files live in Home Assistant's private `.storage/ham_attachments` directory, with UUID names, owner account checks, size and image-type limits, and no `/local` or unauthenticated URL. HAM displays an image in event details only through an authenticated request. The integration and its package declaration are mirrored in `home-assistant/` for the source release; the Home Assistant project tracks the live deployment copy. Keep both copies byte-identical when changing the endpoint.

An unfinished review is encrypted in Android Keystore-backed app preferences, and its image stays in the app-private sandbox. Cancel removes an uploaded image when no calendar write was attempted. After an uncertain write, HAM keeps the attachment and draft for reconciliation rather than deleting an image that may already be linked by an event. Android's `allowBackup=false` means this private state does not have a portable backup; an in-place update preserves it. No analytics or remote OCR service is used.

## Acceptance and release gates

- Automated DEV tests, lint, source privacy checks, and Android package checks must pass.
- The Note8 must receive a higher-code APK signed with its current DEV certificate, in place and without clearing data. Verify the retained Home Assistant connection, calendars, app settings, and screenshot review.
- Use only a dedicated disposable writable calendar for create/read/delete acceptance. Never make a test event in a personal or school calendar. Verify the exact entity ID, visible image, deletion, and no leftovers.
- Publish a DEV APK only after the Note8 acceptance and a privacy audit of the exact signed artifact and matching public source. Verify APK bytes at the public URL, then publish the OTA manifest last.
- The Pixel 8 must take this version through HAM's user-approved OTA path. No ADB installation on that device is authorized for this feature.
- Stable publication and store submissions remain separate, unapproved gates.
