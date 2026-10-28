# Synthetic ICS fixture for HAM device acceptance

`moved-override-no-end.ics` contains a two-occurrence, 90-minute UTC series. Its second occurrence moves to 18:00 UTC on 2026-11-02 and deliberately has no `DTEND` or `DURATION`; a calendar reader should inherit the 90-minute span and display an end of 19:30 UTC. All names, dates, UID and descriptions are synthetic. The file has CRLF line endings and no credentials, private calendar URL or personal event data.

This fixture-only branch is for testing HAM's direct HTTPS read-only ICS subscription on an Android device. It is not an app release, updater feed, account service or user calendar. Remove the test subscription from the device after acceptance.
