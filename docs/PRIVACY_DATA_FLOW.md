# HAM data flow in this source snapshot

HAM connects directly to the Home Assistant address entered by the user. Its OAuth WebView receives the Home Assistant authorization response inside the app. Access and refresh tokens and the server address are held in Android Keystore-backed encrypted private preferences. Calendar lists, events, and open to-do items are loaded into process memory. Local preferences include calendar favorites, hidden calendar IDs, an optional explicit default, recent selections, and connection/refresh times. Android app backup is disabled in the manifest.

Calendar reads and supported create/title-edit requests go to the chosen Home Assistant server. The selected calendar's exact entity ID is used for writes and its current capability is checked before creation. Clearing loaded information removes in-memory data; disconnect removes the local session. Requesting revocation asks Home Assistant to revoke the refresh token and reports whether the request was accepted.

The HAM website is an OAuth client identity and a software distribution site. Home Assistant may fetch that page to identify the app; HAM does not send calendar contents or authorization codes to it. The DEV flavor does not query an update feed. The Direct Stable flavor contains a future official update checker and user-approved installer; Play and F-Droid flavors defer to their distribution channels. This is source-level documentation, not proof of every possible runtime network behavior.

The effective public [Privacy Policy](https://tinkercobble.com/ham/privacy/) describes current user-facing practices. Recheck the runtime behavior and policy whenever data handling changes.
