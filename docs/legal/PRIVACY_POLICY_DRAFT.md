# StationAlarm — Privacy Policy (DRAFT)

> **DRAFT — not legal advice. Owner must review before publishing.**
> Written from the app's source code as of 2026-11-12. It replaces the inaccurate text in the `stationalarm-legal` site (see `PLAY_STORE_READINESS.md` B4).
> **Before publishing:**
> 1. Replace `<CONTACT_EMAIL>` and `<EFFECTIVE_DATE>`.
> 2. Resolve every `VERIFY` below (things the app code cannot prove, e.g. what your Cloudflare Worker and LocationIQ log or retain).
> 3. If you fix R7 (move debug logs to private storage) update section 4; if you add ads or another SDK, rewrite sections 3 and 5.
> 4. Make the Play Console Data safety form match this document.

**Effective date:** `<EFFECTIVE_DATE>` · **Contact:** `<CONTACT_EMAIL>`

StationAlarm is an Android app that wakes you before you reach a railway station or a saved place. This policy explains what data the app uses and where it goes.

## 1. Location data

- **Alarm monitoring (on your device only).** To ring near your destination the app reads your precise location, including in the background while an alarm is armed, using Google Play services location and geofencing. This location is used on your device to decide when to ring. It is not sent to us.
- **Place search and the map (sent to our server).** When you search for a place that is not in the built-in Indian railway station list, the app sends your search text to our geocoding service (a Cloudflare Worker that forwards the request to LocationIQ). When you tap the map to drop a pin, the tapped coordinates are sent for an address lookup. The app also sends your last known location with a search request, and your device language code. `VERIFY:` the code comment says the Worker ignores the location hint. If it does, stop sending it (`MapSearchViewModel.kt`) and delete this sentence.
- Searches for stations in the built-in Indian railway list make no network request.

## 2. What is stored on your device

Your alarms (destination name and coordinates, radius, sound, vibration, notes) and your settings (theme, units, alarm sound, audio options) are stored locally in the app. Removing an alarm deletes it; uninstalling the app deletes everything.

## 3. Services the app talks to

| Service | What it receives | Why |
|---|---|---|
| **Firebase Analytics** (Google) | App events (for example "alarm set", "alarm fired"), screen names, device model, manufacturer, Android version, and an app-instance identifier | Understand reliability and usage |
| **Firebase Crashlytics** (Google) | Crash and error reports with device model, Android version, whether battery optimisation is exempted, and a banded count of your alarms | Fix crashes |
| **Firebase Remote Config** (Google) | The app requests configuration values | Switch backends and show maintenance notices without an app update |
| **Geocoding Worker → LocationIQ** | Search text, tapped map coordinates, language code (see section 1) | Place search and address lookup |
| **OpenStreetMap tile servers** | Standard web request data (your IP address and the map area you view) | Show the map |

Analytics events contain coarse categories only. They never contain your search text, coordinates, addresses or alarm names.

The app does **not** show ads, does not request the Android advertising ID, and does not sell your data.

`VERIFY:` whether the Worker or LocationIQ keeps request logs and for how long, and whether LocationIQ's terms let you describe retention as you intend. Add the answer here.

## 4. Diagnostic log files on your device

The app currently writes diagnostic logs, including location, speed and accuracy readings, as CSV files in your phone's **Downloads/TrainAlarmLogs** folder. They stay on your device unless you share them. Other apps with file access, and you, can read them. You can delete them at any time. `VERIFY / UPDATE:` remove or rewrite this section once logging moves to app-private storage or is disabled in release builds.

## 5. Permissions and why

| Permission | Why |
|---|---|
| Precise location, and location "all the time" | Detect arrival while the phone is locked or the app is closed |
| Notifications, full-screen alerts | Show the alarm and the ongoing monitoring notice |
| Foreground service (location, media playback) | Keep monitoring and play the alarm reliably |
| Vibration, wake lock, boot completed | Ring and re-arm alarms after restart |
| Internet and network state | Search, map tiles, crash and analytics reporting |

## 6. Sharing, retention, deletion

We do not sell personal data. We share data only with the services listed in section 3, acting as processors for the purposes stated. You can erase local data by removing alarms or uninstalling the app. To ask about data held by Firebase or the geocoding service, email `<CONTACT_EMAIL>`; `VERIFY:` state the deletion process and response time you can actually deliver.

## 7. Children

The app is not directed at children under 13, and we do not knowingly collect their data.

## 8. Security and limits

Data sent to our services uses HTTPS. StationAlarm is a convenience tool: location, battery settings, phone makers' power management and network conditions can delay or prevent an alarm. Do not rely on it alone for a journey where a missed stop is dangerous.

## 9. Changes and contact

We will update this page and its effective date when the app's data practices change. Questions: `<CONTACT_EMAIL>`.
