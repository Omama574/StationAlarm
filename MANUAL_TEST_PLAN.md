# StationAlarm — Manual Test Plan

Covers every user-facing change introduced on `feature/glass-ui` (now merged into `main` via PR #2). 13 commits, 35 files, +2139/-631.

Use this as a checklist before releasing the next build to Play Store. Each test case is independent — you can run them in any order, but the **Smoke Test** (Section 0) should pass before the rest.

---

## Test Environment

Minimum coverage needed before release:

| Slot | What | Why |
|---|---|---|
| 1 | A stock Pixel (Android 13+) | Catches generic Android regressions |
| 2 | A Xiaomi/MIUI device (HyperOS or MIUI 14+) | OEM battery manager is the harshest in the wild |
| 3 | A Samsung device (OneUI 6+) | Samsung's Adaptive Battery + Sleeping Apps list |
| 4 | One device on Android 8.x or 9.x | Tests the deprecated screen-wake flag fallback in `AlarmActivity` |

For each: **fresh install** AND **upgrade install** (install the previous Play Store version first, then upgrade — that exercises `MIGRATION_5_6`).

You will also need:
- A Bluetooth speaker or BT headphones.
- Wired headphones (3.5 mm or USB-C).
- Optionally: a Mock Location app (e.g., "Fake GPS Location") for geofence simulation without physically travelling.

---

## 0. Smoke Test (15 min) — must pass before deeper testing

| ID | Test | Expected | Pass? |
|---|---|---|:---:|
| SM-1 | Open app on a fresh install. | No crash. Permission prompts appear in order: Fine Location → Background Location → Notifications (Android 13+). | ☐ |
| SM-2 | Set an alarm on any railway station. | Alarm appears in My Stations tab with the station's name as default. Foreground notification appears in the status bar. | ☐ |
| SM-3 | Toggle the alarm OFF then ON in the My Stations card. | Status badge flips Paused ↔ Monitoring. Toggle does not lie. | ☐ |
| SM-4 | Open Settings → Alarm Sound → tap "Test alarm (3s)". | Alarm plays for ~3 seconds then stops automatically. Foreground notification briefly appears and dismisses. | ☐ |
| SM-5 | Force-stop the app from system Settings → Apps → StationAlarm → Force Stop. Re-open the app. | Active alarms still present in My Stations. App does NOT fire an alarm on launch. | ☐ |
| SM-6 | Reboot the device. Wait ~30s, swipe down → notification shade. | Foreground "Station Alarm" notification is present (if an active alarm exists). | ☐ |

If any of SM-1..SM-6 fails, stop and file a bug before continuing.

---

## 1. Audio Routing — Bluetooth / Wired Headphones

Source: commit `c60ee30 feat: Bluetooth/headphone alarm audio routing`. Behaviour controlled by `service/AlarmAudioController.kt`.

### 1.1 Default behaviour (no external device)

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| AUD-01 | No BT, no wired. Trigger alarm (use Test alarm button). | Sound plays from phone speaker. | ☐ |

### 1.2 With Bluetooth speaker / headphones

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| AUD-02 | Connect BT speaker. Test alarm. | Sound plays from BOTH phone speaker AND BT speaker simultaneously. | ☐ |
| AUD-03 | Settings → Audio Routing → turn OFF "Ring speaker with headphones". Reconnect BT. Test alarm. | Sound plays from BT ONLY. Phone speaker silent. | ☐ |
| AUD-04 | Re-enable the toggle. Test alarm. | Dual routing restored. | ☐ |
| AUD-05 | Start a REAL alarm (not test). While ringing, turn off the BT speaker / move out of range. | Audio keeps playing on the phone speaker. No silent gap. | ☐ |
| AUD-06 | Start an alarm. While ringing, reconnect the BT speaker. | BT speaker takes over (or joins). No crash. | ☐ |

### 1.3 With wired headphones

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| AUD-07 | Plug in wired headphones. Test alarm. With toggle ON: dual route. With toggle OFF: wired only. | Same as AUD-02 / AUD-03 but for wired. | ☐ |
| AUD-08 | Unplug wired headphones mid-alarm. | Sound keeps playing on speaker. | ☐ |

---

## 2. Escalating Alarm Volume + Low-Volume Warning

Source: commit `5a0b9a0 feat: escalating alarm volume and low-volume warning`.

### 2.1 Escalating volume

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| ESC-01 | Settings → Audio Routing → confirm "Start quiet, build to full volume" is ON, ramp = 30 s. | Toggle is ON, slider at 30 s. | ☐ |
| ESC-02 | Trigger a real alarm. | Alarm starts very quiet, audibly ramps up over ~30 s to full volume. | ☐ |
| ESC-03 | Settings → set ramp to 15 s. Trigger alarm. | Alarm ramps up noticeably faster. | ☐ |
| ESC-04 | Settings → set ramp to 60 s. Trigger alarm. | Alarm ramps up much more slowly. | ☐ |
| ESC-05 | Settings → turn escalating OFF. Trigger alarm. | Alarm starts at full volume immediately. | ☐ |

### 2.2 Low-volume warning

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| LOW-01 | Use volume rocker on the lock screen to drop alarm volume below 50 %. Open app. Set a new alarm with Sound enabled. | After confirming, a dialog appears: "Alarm volume is low". | ☐ |
| LOW-02 | Tap "Increase to max". | Alarm volume jumps to maximum. (Verify in system volume controls.) | ☐ |
| LOW-03 | Tap "Keep current" on the dialog. | Dialog dismisses. Volume unchanged. | ☐ |
| LOW-04 | Set alarm volume to ≥50 %. Set a new alarm. | Dialog does NOT appear. | ☐ |
| LOW-05 | Set an alarm with Sound DISABLED (notify/vibrate only). | Dialog does NOT appear regardless of volume. | ☐ |

---

## 3. Battery Optimization Onboarding

Source: commits `cbc856f feat: battery optimization onboarding` and `7709c5d feat(battery): add 'Don't ask again' option`.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| BAT-01 | Fresh install. Confirm app is NOT in the battery-exempt list (system Settings → Battery → Battery Optimization → StationAlarm = Optimized). | App appears as Optimized. | ☐ |
| BAT-02 | Set your first alarm. | After the snackbar, a sheet titled "Keep alarms reliable" appears. | ☐ |
| BAT-03 | Tap "Open settings" on the sheet. | Lands on the system battery-optimization page (or app details). | ☐ |
| BAT-04 | Grant the exemption. Return to the app. Settings → Background Running. | Row immediately shows "Unrestricted — alarms will fire reliably" in green. (Should not require a screen refresh — Phase 5 fix.) | ☐ |
| BAT-05 | Remove the exemption in system Settings. Return to app's Settings screen. | Row immediately updates to "Restricted — alarms may be missed" in red. | ☐ |
| BAT-06 | Set a second alarm WITHOUT the exemption. | Sheet appears again ("Skip for now" path). | ☐ |
| BAT-07 | On the sheet, tap "Don't ask again". Set another alarm. | Sheet does NOT appear. | ☐ |
| BAT-08 | Settings → Background Running → Open. | The Settings deep-link still works (the only remaining way to grant the exemption after Don't-ask-again). | ☐ |

---

## 4. Geofence Wake-Lock Bridge

Source: commit `077d570 fix(geofence): wake lock bridge and service-start error handling`. Hard to test without a Doze-mode setup.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| WL-01 | Set an alarm with a 5 km radius near your location. Lock the device. Wait 30+ minutes (let Doze kick in). Use a Mock Location app to "walk into" the geofence. | Alarm fires within 5–10 seconds of the geofence trigger event. | ☐ |
| WL-02 | Repeat WL-01 with the device on airplane mode → no cellular. Geofence triggers should still work via cached GMS data. | Same — alarm fires. | ☐ |
| WL-03 | Repeat WL-01 on a Xiaomi/MIUI device with Battery Saver ON. | Alarm fires. (Pre-fix this could take 30–90 s or be dropped entirely.) | ☐ |
| WL-04 | Set the alarm. Use `adb shell dumpsys deviceidle force-idle` to force Doze. Trigger geofence. | Alarm fires within a few seconds. Use `adb shell dumpsys deviceidle unforce` to exit. | ☐ |

---

## 5. Ghost Alarm Prevention (after process kill)

Source: commits `ac8b137 fix(alarm): prevent ghost alarms after process kill` + Phase 5 R1 hardening in `4eb756c`.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| GH-01 | Trigger a real alarm (any way). While it's ringing, go to system Settings → Apps → StationAlarm → Force Stop. Then re-open the app. | App opens. Alarm does NOT auto-fire again. The previously-alerting station is back in MONITORING state on the My Stations card. | ☐ |
| GH-02 | Trigger an alarm. Dismiss it. Force-stop the app. Re-open. | The alarm is in PAUSED state (because dismissed = PAUSED). | ☐ |
| GH-03 | Run `adb shell am kill com.omama.stationalarm` while an alarm is ringing. Re-open app. | Same as GH-01 — no ghost alarm. | ☐ |
| GH-04 | While 2 alarms are simultaneously ringing, force-stop. Re-open. | Both stations back to MONITORING. No re-fire of either. | ☐ |

---

## 6. DB Migration v5 → v6

Source: commit `81d6bc9 refactor: drop saved_places, add lastTriggeredAt (DB v5→v6)`.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| DB-01 | Install the **previous** Play-Store build (v5). Set 2–3 alarms. Save 1–2 favourite places from the Map tab (if that feature still existed). Force-quit. | Alarms and places persist. | ☐ |
| DB-02 | Side-load the new build over the v5 install (don't uninstall). Open. | App opens with no crash. All alarms still present in My Stations. | ☐ |
| DB-03 | Verify in `adb shell` (requires `run-as`): `run-as com.omama.stationalarm sqlite3 databases/station_database "SELECT name FROM sqlite_master WHERE type='table'"` | Output lists `active_stations` and `room_master_table` — NO `saved_places`. | ☐ |
| DB-04 | Open the Map tab. | No "Saved Places" sheet/feature visible. Map and search still work. | ☐ |
| DB-05 | Fresh install of the new build. Set an alarm. | Works. No migration code runs (clean v6 DB). | ☐ |

---

## 7. Alarm Name — Editable in Sheet + Inline Rename + Last Triggered

Source: commit `617fb7d feat: editable alarm name + last-triggered timestamp`.

### 7.1 Pre-fill behaviour in the bottom sheet

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| NAM-01 | My Stations tab → search "Bangalore" → tap a result → bottom sheet opens. | "Alarm name" field is pre-filled with the railway station's name (e.g., "Bangalore City Jn"). | ☐ |
| NAM-02 | Map tab → search "Connaught Place" → tap a result → tap Set Alarm → bottom sheet opens. | "Alarm name" pre-filled with the geocoded address from LocationIQ/Photon. | ☐ |
| NAM-03 | Map tab → tap an empty area on the map (no geocoding result) → tap Set Alarm. | "Alarm name" pre-filled with the literal string `Alarm`. | ☐ |
| NAM-04 | In the sheet, clear the name field entirely and tap Set Alarm. | Alarm is created with name `Alarm` (fallback). | ☐ |
| NAM-05 | In the sheet, type a custom name like "Office stop" → tap Set Alarm. | Card on My Stations shows "Office stop". | ☐ |

### 7.2 Inline rename on the card

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| NAM-06 | My Stations → tap the small pencil icon next to an alarm's name. | The name text becomes an editable text field with Check (✓) and Close (✗) buttons. | ☐ |
| NAM-07 | Type a new name → tap ✓. | Name updates immediately on the card. Persists after closing and reopening the app. | ☐ |
| NAM-08 | Tap pencil → change the name → tap ✗. | Name reverts to the original. No DB write. | ☐ |
| NAM-09 | Tap pencil → clear the field → tap ✓. | Name falls back to `Alarm`. | ☐ |

### 7.3 Edit-existing-alarm flow

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| NAM-10 | My Stations → tap Edit on an alarm card. The bottom sheet opens with current values. | Alarm name field shows the current name (not the original station name). | ☐ |
| NAM-11 | Change the name in the sheet → tap "Update alarm". | Snackbar says "Alarm updated for [new name]". The card reflects the new name. | ☐ |

### 7.4 Last Triggered timestamp

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| TRG-01 | Set an alarm. On the card, no "Last triggered" line should appear (fresh alarm). | Card has alert distance text but no "Last triggered: ...". | ☐ |
| TRG-02 | Trigger the alarm (real geofence OR mock location). Dismiss it. Look at the card. | Card now shows "Last triggered: Today HH:mm" with the current local time. | ☐ |
| TRG-03 | Trigger the same alarm next day. | Timestamp updates to "Today HH:mm" again. (The previous "Today" line moves to "Yesterday HH:mm" only if it ages past midnight.) | ☐ |
| TRG-04 | Wait several days, do not re-trigger. Open app. | Card shows "dd MMM HH:mm" (e.g., "21 May 14:35"). | ☐ |

---

## 8. Configurable Alarm Duration

Source: commit `70d8fe3 feat: configurable alarm duration (1-15 min, default 3 min)`.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| DUR-01 | Open Settings. Scroll to "Alarm Duration". | Section is between "Audio Routing" and "Background Running". Default shows "Rings for: 3 min". | ☐ |
| DUR-02 | Drag the slider. | Slider snaps in 1-minute increments. Range 1 min to 15 min. | ☐ |
| DUR-03 | Set duration to 1 min. Trigger a real alarm. **Do not dismiss.** | Alarm rings for exactly 1 minute, then stops on its own. Audio failure notification does NOT appear (clean stop). The alarm card flips to PAUSED. | ☐ |
| DUR-04 | Set duration to 2 min. Trigger an alarm. Dismiss it after 30 s. | Alarm stops immediately on dismiss. No auto-stop fires later. | ☐ |
| DUR-05 | (Optional, time-consuming) Set duration to 15 min. Trigger an alarm. | Rings for 15 minutes then stops. | ☐ |
| DUR-06 | Kill app while alarm is ringing on a 5-min duration. Re-open. | Alarm card is PAUSED (ghost-alarm guard from GH-01). | ☐ |

---

## 9. Test Alarm Button

Source: commit `0d8b084 feat: test alarm button + Crashlytics context tags`.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| TST-01 | Settings → Alarm Sound → tap "Test alarm (3s)". | Foreground notification appears. Configured alarm sound plays for ~3 seconds. Then stops. Notification disappears. | ☐ |
| TST-02 | Tap "Test alarm" while NO real alarms are armed. After it finishes, swipe down notification shade. | No lingering foreground notification. | ☐ |
| TST-03 | Tap "Test alarm" while a real alarm is armed (MONITORING). After test ends, check notification shade. | Foreground notification stays (real alarm is still being monitored). | ☐ |
| TST-04 | With BT speaker connected + dual routing ON, tap Test alarm. | Plays on speaker AND BT for the 3 seconds. | ☐ |
| TST-05 | With escalating ON (any ramp), tap Test alarm. | You should be able to hear the volume ramping during the 3 s (subtle since duration is short). | ☐ |
| TST-06 | Pick a custom alarm sound first, then tap Test alarm. | Plays the custom sound, not the default. | ☐ |
| TST-07 | Pick an invalid/deleted sound (or simulate by revoking SAF access), tap Test. | Falls back to default alarm sound. No crash. | ☐ |

---

## 10. DND Bypass on Alarm Channel

Source: Phase 5 B-6.2 in `4eb756c`.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| DND-01 | Enable Do Not Disturb on the device (system Settings → Sound → DND). Lock the device. Trigger a real alarm. | Full-screen `AlarmActivity` appears over the lock screen, audio plays at alarm volume, vibration runs. DND does NOT suppress it. | ☐ |
| DND-02 | (Android 13+) In system Settings → Notifications → StationAlarm → "Station Alarm Alerts" channel, verify "Override Do Not Disturb" is ON. | The channel's Override DND toggle is ON by default. | ☐ |
| DND-03 | Manually toggle the override OFF in system settings, then trigger alarm with DND on. | Alarm is suppressed (expected — user can override the app's default). | ☐ |

---

## 11. Lock Screen / Screen Wake (Android 8.1+)

Source: existing `AlarmActivity` code, verified in Phase 5 audit.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| LCK-01 | Lock device. Trigger alarm. | Screen turns on. Full-screen `AlarmActivity` appears OVER the lock screen (you can see the Dismiss button without unlocking). | ☐ |
| LCK-02 | LCK-01 on an Android 8.x/9.x device. | Same behaviour (uses deprecated window flags fallback). | ☐ |
| LCK-03 | LCK-01 on Android 13/14. | Same behaviour (uses `setShowWhenLocked` + `setTurnScreenOn` API). | ☐ |
| LCK-04 | Press volume rocker (up, down, or mute) while the alarm screen is showing. | Alarm dismisses. | ☐ |
| LCK-05 | Press the on-screen Dismiss button. | Alarm dismisses. Returns to lock screen. | ☐ |

---

## 12. osmdroid Map Lifecycle

Source: Phase 5 B-2.1 in `4eb756c`. Hard to verify by eye; use Android Studio's Profiler.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| MAP-01 | Open Map tab. Pan and zoom. Switch to another app for 30 s. Switch back. | Map still shows previous view. Panning works. No black tiles for more than 1–2 seconds. | ☐ |
| MAP-02 | Open Map tab. Press Home, lock device for 1 minute. Unlock and return. | Map resumes without redownloading all tiles. | ☐ |
| MAP-03 | (Optional, dev tool) Use Android Studio → Profiler → Memory. Open Map tab, switch away 5 times. | No steady memory growth. Heap dump should not show retained `MapView` instances. | ☐ |
| MAP-04 | `adb shell run-as com.omama.stationalarm du -sh cache/osmdroid/tile` after browsing the map for a few minutes. | Tile cache stays at or below ~5 MB. | ☐ |

---

## 13. Search Pipeline (mapLatest cancellation + error copy)

Source: Phase 5 B-1.2 + B-1.1 in `4eb756c`.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| SRC-01 | Map tab. Quickly type "Mum" then keep typing to "Mumbai" within 1 second. | Final list shows Mumbai results only, never briefly flashes "Mum" results from a slow earlier request. | ☐ |
| SRC-02 | Map tab. Turn off WiFi and mobile data. Search "Bangalore". | Error message: "No internet connection." | ☐ |
| SRC-03 | Map tab. Use a network proxy that delays responses by 30+ s, or use airplane mode for a moment after typing. Search "test". | Error message contains "timed out" (SocketTimeoutException path). | ☐ |
| SRC-04 | Trigger the Cloudflare Worker to 429 (rate limit — can simulate by spamming searches). | Error message: "Too many requests — try again in a moment." | ☐ |
| SRC-05 | (Hard to reproduce naturally) Server-side 503. | Error message: "Search service is down — try again shortly." | ☐ |
| SRC-06 | Search a query that returns no results (e.g., very long random string). | List is empty, no error toast. | ☐ |

---

## 14. Per-Station Concurrency (Mutex)

Source: Phase 5 B-3.2 in `4eb756c`. Stress-test concurrency.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| CON-01 | My Stations. Toggle the same alarm OFF then ON ten times in rapid succession (tap as fast as possible). | Final state matches your last tap. No crash. No duplicate geofences (verify via Logcat: should see balanced `GEOFENCE_REGISTERED` and `GEOFENCE_REMOVED` events). | ☐ |
| CON-02 | Open Edit on an alarm and confirm it just as a real geofence trigger fires. (Trigger via Mock Location during the edit dialog.) | App does not crash. The alarm either fires correctly or the Edit saves cleanly. No half-state. | ☐ |
| CON-03 | Delete an alarm just as it triggers. | App does not crash. Alarm either rings briefly then stops, or the delete wins. No orphan state in DB. | ☐ |

---

## 15. AppRoot / Recomposition Stability

Source: Phase 5 B-9.1 in `4eb756c`.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| RC-01 | Open the alarm config bottom sheet from My Stations. Rotate the device while the sheet is open. Confirm. | No NPE crash. The alarm gets created/updated. | ☐ |
| RC-02 | Open the sheet. While it's open, swipe the app away from Recents, then re-open. | App restarts cleanly. (Sheet state is allowed to be lost.) | ☐ |

---

## 16. Permissions Re-Check on Resume

Source: existing AppRoot DisposableEffect, verified in Phase 5 audit.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| PRM-01 | Grant all permissions. Open app. Go to system Settings → Apps → StationAlarm → Permissions → REVOKE Location. Return to the app. | App detects the missing permission and shows the rationale dialog or re-launches the request. | ☐ |
| PRM-02 | Revoke Notifications permission (Android 13+) while app is open. Return. | NotificationPermissionDialog appears. | ☐ |
| PRM-03 | Revoke Background Location. Return. | PermissionRationaleDialog appears. | ☐ |
| PRM-04 | Grant back all permissions and return. | Dialogs dismiss automatically. | ☐ |

---

## 17. Firebase Resilience

Source: Phase 5 B-4.1 in `4eb756c`. Hard to test without breaking your `google-services.json`.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| FB-01 | Temporarily rename `app/google-services.json` to `google-services.json.bak`, then `./gradlew assembleDebug` (you may need to comment out the `google-services` plugin to compile). Install and open. | App does not crash on launch. Alarms still work. Logcat shows "Firebase init failed — running without Crashlytics/RemoteConfig". | ☐ |
| FB-02 | Restore `google-services.json`. Rebuild. Verify Crashlytics is initialized (look for `FirebaseCrashlytics` in Logcat). | Normal operation. | ☐ |

---

## 18. Crashlytics Context Tags (release builds only)

Source: Phase 5 S7 in `0d8b084`. Cannot be verified in debug builds (`if (!BuildConfig.DEBUG)`).

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| CL-01 | Build a release variant (`./gradlew assembleRelease`). Install on a device. Trigger an alarm and dismiss it. | No crash. (You cannot directly verify the keys without triggering a real crash mid-alarm; just confirm no regressions.) | ☐ |
| CL-02 | Force a crash mid-alarm (e.g., add a `throw RuntimeException()` temporarily inside `fireAlert` after the keys are set). Look at the Crashlytics dashboard after the next launch. | The crash report shows custom keys: `alarm_station_id`, `alarm_battery_exempt`, `alarm_gps_accuracy_m`, `alarm_audio_route`, `alarm_escalating`, `alarm_duration_secs`. | ☐ |

---

## 19. OEM Reliability Smoke (15–30 min each device)

The single most important section. Skim everything else if you must, but do this on a Xiaomi AND a Samsung device for every release.

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| OEM-01 | Xiaomi/MIUI: set a 3 km alarm 3 km away. Grant battery exemption. Enable "Auto-start" in Security app for StationAlarm. Lock the device. Wait 1 hour. Mock-walk into the geofence. | Alarm fires within 10 seconds. | ☐ |
| OEM-02 | Xiaomi/MIUI: same as OEM-01 but with Battery Saver ON. | Alarm still fires (within 30 s acceptable). | ☐ |
| OEM-03 | Samsung/OneUI: same as OEM-01. Ensure StationAlarm is NOT in "Sleeping apps". | Alarm fires within 10 seconds. | ☐ |
| OEM-04 | Samsung/OneUI: enable Adaptive Battery. Don't open the app for 24 h. Re-test geofence. | Alarm still fires. | ☐ |
| OEM-05 | Any OEM: reboot the device. Without opening the app, wait for `BootReceiver` to run (~30 s after boot). Mock-walk into a previously-set geofence. | Alarm fires. Verify in `Logcat`: `BOOT_RESTORE` followed by `GEOFENCE_REGISTERED`. | ☐ |

---

## 20. Regression Spot-Checks (existing features that must still work)

| ID | Steps | Expected | Pass? |
|---|---|---|:---:|
| REG-01 | Map tab → tap empty area → set alarm. | Custom alarm appears in My Stations with lat/lon and "Alarm" as default name. | ☐ |
| REG-02 | My Stations → Map button on a card. | Opens Map tab centered on that alarm with the radius circle drawn. | ☐ |
| REG-03 | My Stations → Delete an alarm. Confirm. | Card disappears. Foreground notification disappears if it was the last alarm. | ☐ |
| REG-04 | Set an alarm with a custom reminder note + "Show reminder when alarm fires" ON. Trigger the alarm. | Full-screen `AlarmActivity` shows the reminder text inside a Card. | ☐ |
| REG-05 | Settings → Distance Unit → Miles. Return to a card. | Distance text and "Alerts within" text use miles. | ☐ |
| REG-06 | Settings → Theme → Light / Dark / System. | UI switches accordingly. | ☐ |
| REG-07 | Drawer (hamburger menu) → Rate Us. | Opens Play Store (or browser fallback). | ☐ |
| REG-08 | Drawer → Share App. | System share sheet opens. | ☐ |
| REG-09 | Drawer → About. | About screen opens; back button returns. | ☐ |
| REG-10 | Drawer → Settings → Debug Logs → App Logs / GPS Logs. | Share intent opens for the CSV file. | ☐ |

---

## 21. Unit Tests (automated, no device needed)

Source: commit `5212259 test: add Robolectric-based unit tests for DAO, prefs, and error mapping`.

| ID | Command | Expected | Pass? |
|---|---|---|:---:|
| UT-01 | `./gradlew testDebugUnitTest` | All 19 tests pass. Output: 0 failures, 0 errors. | ☐ |
| UT-02 | After any code change to `data/db/`, `data/UserPreferences.kt`, or `ui/viewmodel/MapSearchViewModel.kt`, re-run UT-01. | Still passes. | ☐ |

---

## 22. Compile / Build / Install

| ID | Command | Expected | Pass? |
|---|---|---|:---:|
| BLD-01 | `./gradlew compileDebugKotlin` | BUILD SUCCESSFUL. Only the existing `getParcelableExtra` deprecation warning. | ☐ |
| BLD-02 | `./gradlew assembleDebug` | APK builds in `app/build/outputs/apk/debug/`. | ☐ |
| BLD-03 | `./gradlew assembleRelease` (requires keystore.properties) | Release APK builds, signed, R8/ProGuard runs without errors. | ☐ |
| BLD-04 | Install the release APK manually. Open. | App launches, no obfuscation crash. | ☐ |

---

## Severity Legend (for filing bugs)

- **P0 (blocker)** — Alarm fails to fire, crash on launch, data loss. Smoke-test failure. Cannot ship.
- **P1 (critical)** — Feature broken in a way users will notice (wrong copy, missed sound, UX dead-end). Ship-blocking unless explicitly accepted.
- **P2 (minor)** — Cosmetic, edge-case, missing optimization. Can ship and fix later.

---

## Sign-off

| Tester | Device | OS | Build SHA | Date | Pass count | Fail count | Notes |
|---|---|---|---|---|---|---|---|
|  |  |  |  |  |  |  |  |
|  |  |  |  |  |  |  |  |

Once every P0/P1 case in the relevant device row is passing, the build is ready for Play Store internal track.
