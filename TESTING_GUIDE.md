# StationAlarm - Geofence & Doze Wake-Up Testing Guide

This guide covers how to verify that StationAlarm's geofence-triggered wake-up works correctly, including under Android Doze mode and after OEM process kills.

---

## 1. Architecture Overview

### 1.1 Geofence Layers

StationAlarm registers **6 concentric geofences** per station via Google Play Services. Each is an ENTER-only trigger with 0ms responsiveness and no expiration.

| Layer   | Radius Formula         | Example (alertDist = 5 km) | Purpose                        |
|---------|------------------------|----------------------------|---------------------------------|
| level5  | alertDistanceKm + 60   | 65 km                      | Outermost early-warning ring    |
| level4  | alertDistanceKm + 40   | 45 km                      | Escalation ring                 |
| level3  | alertDistanceKm + 30   | 35 km                      | Mid-range ring                  |
| level2  | alertDistanceKm + 5    | 10 km                      | Close approach ring             |
| level1  | alertDistanceKm        | 5 km                       | Inner ring (matches alert dist) |
| alert   | alertDistanceKm (min 100m) | 5 km                   | Alarm ring (fires the alert)    |

The `alertDistanceKm` is user-configurable from 3.0 to 20.0 km.

Geofence IDs follow the pattern: `geofence_<stationId>_<layer>` (e.g., `geofence_NDLS_level3`).

### 1.2 Wake-Up Chain

When the device enters a geofence radius, this chain fires — even if the app is killed:

```
Google Play Services detects geofence ENTER
         |
         v
PendingIntent fires (survives process death)
         |
         v
GeofenceBroadcastReceiver.onReceive()
  - goAsync() for background processing
  - Parses geofence requestId -> stationId + layer
  - Verifies station is still active in DB
  - Logs: GEOFENCE_TRIGGERED
         |
         v
ContextCompat.startForegroundService(ACTION_GEOFENCE_TRIGGERED or ACTION_ALERT_GEOFENCE_TRIGGERED)
         |
         v
LocationService.onStartCommand()
  - Immediately calls startForeground() (Android requirement)
  - Non-alert layers: restartLocationUpdates() -> forces immediate GPS poll
  - Alert layer: markAlerting() -> syncWithDatabase() -> fireAlert()
         |
         v
For alert layer:
  - AlarmAudioController plays alarm sound + vibration
  - ServiceNotifications shows full-screen intent
  - AlarmActivity appears (even on lock screen)
  - ServiceWakeLocks acquires PARTIAL_WAKE_LOCK (10 min cap)
```

### 1.3 Log Events (in order of the chain)

These events are written to both Logcat and the CSV log file (`Downloads/TrainAlarmLogs/`):

| Event                        | Source                    | Logcat Tag     | Meaning                                           |
|------------------------------|---------------------------|----------------|----------------------------------------------------|
| `GEOFENCE_TRIGGERED`         | GeofenceBroadcastReceiver | GeofenceBR     | Geofence PendingIntent received                    |
| `SERVICE_GEOFENCE_RECEIVED`  | LocationService           | (Logger CSV)   | Service received the non-alert geofence intent     |
| `SERVICE_ALERT_GEOFENCE_RECEIVED` | LocationService      | (Logger CSV)   | Service received the alert-layer geofence intent   |
| `GPS_STARTED`                | LocationService           | (Logger CSV)   | GPS polling started/restarted                      |
| `WAKELOCK_ACQUIRED`          | ServiceWakeLocks          | (Logger CSV)   | GpsWakeLock acquired                               |
| `MODE_CHANGED`               | LocationService           | (Logger CSV)   | Polling interval changed (e.g., 600000ms -> 10000ms) |
| `LOCATION_POLL`              | LocationService           | (Logger CSV)   | GPS fix received, distance calculated              |
| `ALERT_FIRED`                | LocationService           | (Logger CSV)   | Alarm triggered for a station                      |
| `ALARM_TIMEOUT`              | LocationService           | (Logger CSV)   | Auto-dismissed after 5 minutes                     |
| `ALERT_DISMISSED`            | LocationService           | (Logger CSV)   | User dismissed the alarm                           |
| `WATCHDOG_TRIGGERED`         | LocationService           | (Logger CSV)   | GPS stalled, watchdog restarted it                  |
| `SYSTEM_BOOTED`              | BootReceiver              | BootReceiver   | Device rebooted, restoring alarms                  |
| `BOOT_RESTORE`               | BootReceiver              | BootReceiver   | Geofences re-registered after boot                 |

### 1.4 Polling Intervals (Speed-Adaptive)

Once GPS polling is active, the interval adapts based on distance to the nearest station:

| Distance to Station | Polling Interval |
|----------------------|------------------|
| <= 5 km              | 10 seconds       |
| 5 - 15 km            | 30 seconds       |
| 15 - 30 km           | 1 minute         |
| 30 - 60 km           | 5 minutes        |
| > 60 km              | 10 minutes       |

---

## 2. Critical Concepts Before Testing

### 2.1 `am kill` vs `am force-stop` (IMPORTANT)

| Command | What It Does | Geofences Survive? | Use For Testing? |
|---------|-------------|--------------------|--------------------|
| `adb shell am kill com.omama.stationalarm` | Sends SIGKILL to the process. PendingIntents and geofences remain registered with Play Services. | YES | YES - simulates OEM/memory-pressure kill |
| `adb shell am force-stop com.omama.stationalarm` | Kills process AND removes all PendingIntents, unregisters geofences, clears alarms. | NO | NO - simulates user Settings -> Force Stop. Geofences are gone. |

**Always use `am kill` for testing. Never use `am force-stop`.**

### 2.2 The Doze + Screen-On Paradox

Android Doze mode has a critical constraint: **turning the screen ON immediately exits Doze.**

Even if you forced Doze via `adb shell dumpsys deviceidle force-idle`, the moment the screen turns on, the `DeviceIdleController` receives a `SCREEN_ON` broadcast and transitions back to `ACTIVE`. This means:

1. You force Doze from your laptop terminal
2. You pick up the phone to open a FakeGPS app to change location
3. Screen turns on -> **Doze is gone**
4. You are no longer testing what you think you are testing

**Solution**: Use a mock location app that supports **pre-planned timed routes** running as a **background service**. Set everything up while the screen is ON, start the route, THEN turn off the screen and force Doze. The mock location service keeps feeding coordinates even with the screen off.

### 2.3 USB Charging Blocks Doze

A device that is charging will NOT enter Doze mode. Since your phone is connected via USB for ADB, you must fake the battery state:

```bash
# Fake unplug (device still physically connected but OS thinks it's on battery)
adb shell dumpsys battery unplug

# Reset to real state when done
adb shell dumpsys battery reset
```

### 2.4 Foreground Service vs Doze

If your app has an active foreground service, the system does NOT consider it "idle". To test the wake-from-killed-state scenario, ensure the LocationService is NOT running before forcing Doze.

---

## 3. Emulator Testing (Start Here)

The emulator is the easiest environment to test geofence + Doze because `adb emu geo fix` injects location at the system level — no mock location app needed, works with screen off.

### Prerequisites

- Android Studio emulator running (Pixel device image, API 33+)
- StationAlarm installed on emulator
- ADB connected: `adb devices` shows the emulator

### 3.1 Setup

```bash
# 1. Verify emulator is connected
adb devices
# Should show something like: emulator-5554  device

# 2. Install the app (if not already)
cd c:/Users/omama/AndroidStudioProjects/StationAlarm
./gradlew installDebug
```

### 3.2 Test Scenario A: Basic Geofence Wake-Up (No Doze)

This is the simplest test — just verify the geofence -> broadcast -> service chain works.

**Step 1: Choose a test station and set your initial position far away.**

Pick any Indian railway station. Example: New Delhi (NDLS) at lat=28.6424, lon=77.2194.

```bash
# Set emulator location FAR from the station (e.g., Mumbai: 19.0760, 72.8777)
adb emu geo fix 72.8777 19.0760
```
> Note: `geo fix` takes longitude first, then latitude.

**Step 2: Open the app, set an alarm for New Delhi station with alert distance 5 km.**

**Step 3: Start monitoring logcat.**

Open a NEW terminal window and run:
```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

**Step 4: Move into the outermost geofence (level5 = 65 km from station).**

```bash
# Somewhere ~60 km from New Delhi (inside level5, outside level4)
# Approx: Meerut area (28.98, 77.70)
adb emu geo fix 77.70 28.98
```

**Step 5: Watch logcat.**

You should see within 30 seconds:
```
GeofenceBR: Geofence triggered: NDLS, layer=level5
```
Followed by:
```
SERVICE_GEOFENCE_RECEIVED in the Logger CSV
GPS_STARTED
```

**Step 6: Move into progressively smaller geofences.**

```bash
# ~30 km from NDLS (inside level3)
adb emu geo fix 77.40 28.75

# ~8 km from NDLS (inside level2)
adb emu geo fix 77.28 28.70

# ~3 km from NDLS (inside alert zone)
adb emu geo fix 77.22 28.65
```

**Step 7: On entering the alert zone, verify:**
- Alarm sound plays
- Full-screen notification appears
- Logcat shows `ALERT_FIRED`

### 3.3 Test Scenario B: App Killed + Geofence Wake-Up

**Step 1: Set up an alarm (same as Scenario A, Step 1-2). Set initial position far away.**

```bash
adb emu geo fix 72.8777 19.0760
```

**Step 2: Kill the app process.**

```bash
# IMPORTANT: Use "am kill", NOT "am force-stop"
adb shell am kill com.omama.stationalarm
```

**Step 3: Verify the app is dead.**

```bash
adb shell ps | grep stationalarm
# Should return nothing
```

**Step 4: Start logcat monitoring (new terminal).**

```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

**Step 5: Move into a geofence.**

```bash
adb emu geo fix 77.22 28.65
```

**Step 6: Expected result:**

Logcat should show the full wake-up chain:
```
GeofenceBR: Geofence triggered: <stationId>, layer=<layer>
```
Then the service starts from cold:
```
GPS_STARTED
LOCATION_POLL
```

**Step 7: Verify the service is running.**

```bash
adb shell dumpsys activity services com.omama.stationalarm | head -20
```

You should see `LocationService` listed as a running foreground service.

### 3.4 Test Scenario C: Deep Doze + Geofence Wake-Up

**Step 1: Set up alarm, position far away (same as above).**

**Step 2: Kill the app.**

```bash
adb shell am kill com.omama.stationalarm
```

**Step 3: Fake battery unplug (emulator may already be "unplugged", but be safe).**

```bash
adb shell dumpsys battery unplug
```

**Step 4: Turn off the emulator screen.**

```bash
adb shell input keyevent KEYCODE_POWER
```

**Step 5: Force deep Doze.**

```bash
# Step through Doze states
adb shell dumpsys deviceidle step deep
adb shell dumpsys deviceidle step deep
adb shell dumpsys deviceidle step deep
adb shell dumpsys deviceidle step deep

# Or force it directly
adb shell dumpsys deviceidle force-idle
```

**Step 6: Verify Doze is active.**

```bash
adb shell dumpsys deviceidle | grep mState
# Should show: mState=IDLE
```

**Step 7: Start logcat monitoring (new terminal).**

```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

**Step 8: Inject location inside a geofence.**

```bash
# Inside the alert zone for New Delhi
adb emu geo fix 77.22 28.65
```

**Step 9: Wait and watch.**

Geofence triggers may be delayed by 2-6 minutes in Doze (location updates are batched). Be patient.

**Step 10: Expected result:**

Same wake-up chain as Scenario B. The BroadcastReceiver fires, starts the foreground service, GPS polling begins.

**Step 11: Cleanup.**

```bash
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell input keyevent KEYCODE_POWER   # screen back on
```

---

## 4. Physical Device Testing

Physical devices are the real test — especially OEM devices (Xiaomi, Samsung, OnePlus) that aggressively kill background apps.

### 4.1 Prerequisites

- Physical Android device (Android 10+)
- USB debugging enabled
- ADB connected: `adb devices` shows your device
- **Lockito** app installed from Play Store (free, supports background route simulation)

### 4.2 One-Time Setup

**A. Enable Developer Options:**
Settings -> About Phone -> Tap "Build Number" 7 times.

**B. Enable Mock Locations:**
Settings -> Developer Options -> "Select mock location app" -> Choose **Lockito**.

**C. Verify ADB connection:**
```bash
adb devices
# Should show your device serial number
```

**D. Whitelist Lockito from battery optimization:**

This is CRITICAL. If the OEM kills Lockito while it's simulating your route, the test is ruined.

- Settings -> Battery -> Battery Optimization -> All Apps -> Lockito -> "Don't optimize"
- On Xiaomi: Settings -> Battery & Performance -> App Battery Saver -> Lockito -> No restrictions
- On Samsung: Settings -> Battery -> Background usage limits -> Never sleeping apps -> Add Lockito
- On OnePlus: Settings -> Battery -> Battery Optimization -> Lockito -> Don't optimize

### 4.3 Choose Test Coordinates

Pick a station you want to test. You need two GPS points:

| Point | What | Example (New Delhi / NDLS) |
|-------|------|----------------------------|
| START | Far outside the outermost geofence (>65 km from station) | 19.0760, 72.8777 (Mumbai) |
| END   | Inside the alert geofence | 28.6400, 77.2200 (near NDLS) |

### 4.4 Test Scenario D: Physical Device - App Killed + Doze + Geofence

This is the ultimate test. App is killed, device is in deep Doze, and the geofence must wake everything up.

#### Phase 1: Setup (Screen ON)

**Step 1: Open Lockito, create a route.**

- Tap the "+" button to create a new route
- Place the START point far from your station (e.g., Mumbai)
- Place the END point at/near your station (e.g., New Delhi)
- Set speed: **100 km/h** (simulates train speed)
- The route will take a while to "travel" from Mumbai to Delhi — that's fine, it gives you time to set up Doze
- If you want a faster test, pick a start point that's closer but still outside the outermost geofence (e.g., 70 km from the station)

**Step 2: Start the Lockito route.**

Tap "Start" in Lockito. It begins injecting GPS coordinates as a background service.

**Step 3: Open StationAlarm, set an alarm for the target station.**

Set alert distance to 5 km (or your preferred value).

**Step 4: Verify geofences are registered.**

```bash
# Check if the app's geofences exist in Play Services
adb shell dumpsys activity service com.google.android.gms | grep -A 5 "stationalarm"
```

**Step 5: Kill the StationAlarm app.**

```bash
adb shell am kill com.omama.stationalarm
```

**Step 6: Verify the app is dead.**

```bash
adb shell ps | grep stationalarm
# Should return nothing
```

#### Phase 2: Doze (Screen OFF, all from laptop terminal)

**Step 7: Lock the screen.**

```bash
adb shell input keyevent KEYCODE_POWER
```

**Step 8: Fake battery unplug.**

```bash
adb shell dumpsys battery unplug
```

**Step 9: Force deep Doze.**

```bash
adb shell dumpsys deviceidle force-idle
```

**Step 10: Verify Doze is active.**

```bash
adb shell dumpsys deviceidle | grep mState
# Expected: mState=IDLE
```

#### Phase 3: Monitor (from laptop terminal)

**Step 11: Start logcat monitoring (new terminal window).**

```bash
adb logcat -s GeofenceBR:D LocationService:D BootReceiver:D StationAlarm:D
```

**Step 12: Wait for the route to reach the geofence.**

Lockito is still running in the background, feeding mock coordinates. When the simulated position enters the 65 km geofence radius:
- Play Services detects the geofence ENTER
- Fires the PendingIntent
- `GeofenceBroadcastReceiver` wakes up
- Starts `LocationService` via `startForegroundService()`

**Step 13: Check logcat for the wake-up chain.**

You should see (in order):
```
GeofenceBR: Geofence triggered: <stationId>, layer=level5
```
Then as the route continues inward:
```
GeofenceBR: Geofence triggered: <stationId>, layer=level4
GeofenceBR: Geofence triggered: <stationId>, layer=level3
GeofenceBR: Geofence triggered: <stationId>, layer=level2
GeofenceBR: Geofence triggered: <stationId>, layer=level1
GeofenceBR: Geofence triggered: <stationId>, layer=alert
```

And the alarm should fire (sound from the phone even with screen off).

**Step 14: Verify the service is running.**

```bash
adb shell dumpsys activity services com.omama.stationalarm
```

Look for:
```
* ServiceRecord{... com.omama.stationalarm/.service.LocationService}
  isForeground=true
```

#### Phase 4: Cleanup

```bash
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
# Turn screen on
adb shell input keyevent KEYCODE_POWER
```

Stop the Lockito route in the app.

---

## 5. Test Scenarios Matrix

| # | Scenario | Pre-conditions | Action | Expected Result | Verify With |
|---|----------|---------------|--------|-----------------|-------------|
| 1 | App running + geofence enter | App open, alarm set | Move into geofence radius | Service escalates polling interval | Logcat: `SERVICE_GEOFENCE_RECEIVED`, `MODE_CHANGED` |
| 2 | App killed + geofence enter | `am kill`, alarm set | Move into geofence radius | BroadcastReceiver wakes, service starts from cold | Logcat: `GeofenceBR` + `SERVICE_GEOFENCE_RECEIVED` + `GPS_STARTED` |
| 3 | Deep Doze + geofence enter | Force idle, alarm set | Move into geofence radius (may take 2-6 min) | Broadcast received, service starts | Logcat: `GEOFENCE_TRIGGERED` + `GPS_STARTED` |
| 4 | App killed + Deep Doze | `am kill` + `force-idle` | Move into geofence radius | Full cold wake-up chain | Logcat: full chain from `GeofenceBR` to `GPS_STARTED` |
| 5 | Alert geofence (innermost) | Alarm set, approach station | Enter alert radius | Alarm fires (sound + fullscreen) | `ALERT_FIRED` in logcat/CSV, AlarmActivity visible |
| 6 | Boot recovery | Active alarm set, reboot device | `adb reboot` | BootReceiver re-registers geofences, service starts | Logcat: `SYSTEM_BOOTED` + `BOOT_RESTORE` + `SERVICE_INIT_REQUESTED` |
| 7 | Watchdog recovery | Service running, GPS stalled | Block GPS for >60s (Airplane mode toggle) | Watchdog detects stall, restarts GPS | Logcat: `WATCHDOG_TRIGGERED` |

> Scenarios 1-4 have full step-by-step walkthroughs in Section 3 (emulator) and Section 4 (physical device). Scenarios 5-7 are below.

### 5.1 Scenario 5: Alert Geofence (Innermost Ring) — Full Alarm Verification

This tests the complete alarm experience: sound, vibration, full-screen notification, AlarmActivity, and auto-dismiss.

Works on both emulator and physical device.

**Step 1: Start logcat monitoring (Terminal 1).**

```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

**Step 2: Set initial position far from the station.**

```bash
# Emulator:
adb emu geo fix 72.8777 19.0760

# Physical device: set Lockito start point far away, or use FakeGPS to place yourself far away
```

**Step 3: Open StationAlarm, set an alarm for New Delhi (NDLS).**

- Alert distance: **5 km**
- Enable: Sound ON, Vibrate ON
- Tap "Start Trip"

**Step 4: Verify the service is running and geofences are registered.**

```bash
# Service running?
adb shell dumpsys activity services com.omama.stationalarm | head -5

# Geofences registered?
adb shell dumpsys activity service com.google.android.gms | grep -i "stationalarm" | head -10
```

**Step 5: Jump directly into the alert zone (skip outer rings for this test).**

```bash
# Emulator — place yourself ~3 km from NDLS (inside the 5 km alert radius):
adb emu geo fix 77.2100 28.6300

# Physical device — in Lockito, set a short route that ends inside the alert radius,
# or use FakeGPS to jump directly to a point ~3 km from the station
```

**Step 6: Wait for the geofence trigger (up to 30 seconds normally, 2-6 min in Doze).**

**Step 7: Verify the FULL alert chain in logcat (Terminal 1).**

You should see these events in order:
```
GeofenceBR: Geofence triggered: <stationId>, layer=alert
```
Then in the Logger CSV / logcat:
```
SERVICE_ALERT_GEOFENCE_RECEIVED
ALERT_FIRED
WAKELOCK_ACQUIRED (AlarmWakeLock)
```

**Step 8: Verify the alarm UI.**

- **Sound**: alarm tone should be playing from the phone speaker
- **Vibration**: phone should be vibrating
- **Full-screen notification**: should appear even on lock screen (check `USE_FULL_SCREEN_INTENT` permission)
- **AlarmActivity**: if the screen is on, AlarmActivity should be visible

```bash
# Verify AlarmActivity is in the foreground
adb shell dumpsys activity activities | grep -i "AlarmActivity"

# Verify the notification exists
adb shell dumpsys notification | grep -A 5 "stationalarm"
```

**Step 9: Wait 5 minutes WITHOUT dismissing — verify auto-dismiss.**

The alarm has a 5-minute auto-dismiss timeout. After 5 minutes:
```
ALARM_TIMEOUT in logcat
```

The sound and vibration should stop automatically.

**Step 10: Alternative — dismiss manually.**

If testing manual dismiss, tap the dismiss button on the AlarmActivity. Verify:
```
ALERT_DISMISSED in logcat
WAKELOCK_RELEASED (GpsWakeLock)
```

**Step 11: Verify the station was removed from active monitoring.**

```bash
adb shell dumpsys activity services com.omama.stationalarm
# If no other stations are being monitored, the service should stop itself.
# Logcat should show: SERVICE_STOPPING
```

---

### 5.2 Scenario 6: Boot Recovery — Geofences Survive Reboot

This verifies that after a device reboot, the BootReceiver re-registers all geofences and restarts the LocationService.

**Step 1: Open StationAlarm, set an alarm for any station. Confirm it's active.**

```bash
# Verify service is running
adb shell dumpsys activity services com.omama.stationalarm | head -10
```

**Step 2: Start logcat monitoring (Terminal 1).**

After reboot, logcat connection will drop and reconnect. Start it now so it reconnects automatically:
```bash
adb logcat -s BootReceiver:D GeofenceBR:D LocationService:D StationAlarm:D
```

**Step 3: Reboot the device.**

```bash
adb reboot
```

**Step 4: Wait for the device to boot (1-2 minutes).**

ADB will disconnect during reboot. It reconnects automatically once the device is back.

```bash
# Wait for device to reappear
adb wait-for-device
```

**Step 5: Reconnect logcat if needed.**

```bash
adb logcat -s BootReceiver:D GeofenceBR:D LocationService:D StationAlarm:D
```

**Step 6: Verify the boot recovery chain in logcat.**

You should see these events (possibly already logged by the time you reconnect — check the logcat buffer):
```
BootReceiver: Device booted, restoring active alarms...
SYSTEM_BOOTED
BOOT_RESTORE — Restored N stations
SERVICE_INIT_REQUESTED
GPS_STARTED
```

**Step 7: Verify the service is running again.**

```bash
adb shell dumpsys activity services com.omama.stationalarm | head -10
# Should show LocationService as isForeground=true
```

**Step 8: Verify geofences were re-registered.**

```bash
adb shell dumpsys activity service com.google.android.gms | grep -i "stationalarm" | head -10
```

**Step 9: Verify any ALERTING stations were reset to MONITORING.**

The BootReceiver resets stations that were in ALERTING state back to MONITORING (because the user may have rebooted after passing the station). If you had an alerting station before reboot, confirm it's now monitoring:
```bash
# The notification text should say "Monitoring X stations..." not "ALERTING"
adb shell dumpsys notification | grep -A 3 "stationalarm"
```

**Step 10: Test that the restored geofences actually work.**

After boot recovery, move into a geofence to confirm the re-registered geofences fire correctly:
```bash
# Emulator:
adb emu geo fix 77.22 28.65

# Physical device: use Lockito/FakeGPS
```

Watch logcat for `GeofenceBR: Geofence triggered` — this proves the geofences survived the reboot cycle.

---

### 5.3 Scenario 7: Watchdog Recovery — GPS Stall Detection

The LocationService has a 30-second watchdog timer. If no GPS update arrives within `max(60 seconds, pollingInterval * 3)`, the watchdog force-restarts GPS polling.

**Step 1: Set up an alarm and confirm the service is polling.**

```bash
# Emulator: place yourself ~20 km from the station (30-second polling interval)
adb emu geo fix 77.40 28.75
```

Open StationAlarm, set alarm for NDLS.

**Step 2: Start logcat monitoring (Terminal 1).**

```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

Confirm you see `LOCATION_POLL` events arriving at regular intervals (every 10-30 seconds depending on distance).

**Step 3: Stall the GPS by enabling airplane mode.**

```bash
# Enable airplane mode (disables GPS, WiFi, cellular)
adb shell cmd connectivity airplane-mode enable

# Alternative if the above doesn't work on your device:
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
```

> Note: On emulator, airplane mode may not fully block the mock GPS. On physical devices, this reliably stops GPS fixes.

**Step 4: Wait for the watchdog to trigger.**

The watchdog checks every 30 seconds. The timeout is `max(60s, currentPollingInterval * 3)`.

- If polling at 10s interval: timeout = 60s (minimum)
- If polling at 30s interval: timeout = 90s
- If polling at 5min interval: timeout = 15min

For a quick test, make sure you're close to the station (10s polling) so the watchdog triggers after ~60 seconds of no GPS.

**Step 5: Watch logcat for the watchdog.**

After the timeout period, you should see:
```
WATCHDOG_TRIGGERED — GPS stalled for Xs. Restarting.
GPS_STOPPED
GPS_STARTED
```

A watchdog notification will also appear on the device.

**Step 6: Disable airplane mode to restore GPS.**

```bash
adb shell cmd connectivity airplane-mode disable

# Alternative:
adb shell settings put global airplane_mode_on 0
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
```

**Step 7: Verify GPS polling resumes.**

After disabling airplane mode, the restarted GPS should start getting fixes again:
```
LOCATION_POLL events resume in logcat
```

**Step 8: Verify the watchdog notification appeared.**

```bash
adb shell dumpsys notification | grep -A 5 "watchdog"
```

**Step 9 (bonus): Test watchdog during Doze.**

Combine this with Doze to test the worst case — GPS stalled while in Doze:

```bash
adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_POWER
adb shell dumpsys deviceidle force-idle
adb shell cmd connectivity airplane-mode enable
# Wait for watchdog timeout...
# Check logcat for WATCHDOG_TRIGGERED
adb shell cmd connectivity airplane-mode disable
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
```

---

## 6. Logcat & Diagnostics Cheat Sheet

### 6.1 Logcat Filters

```bash
# Watch all StationAlarm events (recommended during testing)
adb logcat -s GeofenceBR:D LocationService:D BootReceiver:D StationAlarm:D

# Geofence events only
adb logcat -s GeofenceBR:D

# Service lifecycle
adb logcat | grep -E "startForeground|onStartCommand|onDestroy|SERVICE_"

# GPS polling
adb logcat | grep -E "GPS_STARTED|GPS_STOPPED|LOCATION_POLL|MODE_CHANGED"

# Alert events
adb logcat | grep -E "ALERT_FIRED|ALARM_TIMEOUT|ALERT_DISMISSED"

# Wake locks
adb logcat | grep -E "WAKELOCK_ACQUIRED|WAKELOCK_RELEASED"

# Everything from the app (verbose, lots of output)
adb logcat | grep stationalarm
```

### 6.2 Service Status

```bash
# Check if LocationService is running
adb shell dumpsys activity services com.omama.stationalarm

# Look for:
#   isForeground=true
#   app=ProcessRecord{... com.omama.stationalarm}
```

### 6.3 Process Status

```bash
# Check if the app process exists
adb shell ps | grep stationalarm

# If empty = process is dead (killed by OS or am kill)
# If shows a line = process is alive
```

### 6.4 Doze Status

```bash
# Current Doze state
adb shell dumpsys deviceidle | grep mState

# Possible states:
#   ACTIVE          - Normal operation
#   IDLE_PENDING    - About to enter idle
#   SENSING         - Checking if device is stationary
#   LOCATING        - Getting last location before idle
#   IDLE            - Deep doze active
#   IDLE_MAINTENANCE - Brief window for deferred work

# Force deep Doze
adb shell dumpsys deviceidle force-idle

# Exit Doze
adb shell dumpsys deviceidle unforce
```

### 6.5 Battery Simulation

```bash
# Fake unplug (required for Doze while USB-connected)
adb shell dumpsys battery unplug

# Check battery status
adb shell dumpsys battery

# Reset to real state
adb shell dumpsys battery reset
```

### 6.6 Geofence Registration

```bash
# Check if geofences are registered in Play Services
adb shell dumpsys activity service com.google.android.gms | grep -i geofence

# Check PendingIntents
adb shell dumpsys activity service com.google.android.gms | grep -A 3 "stationalarm"
```

### 6.7 CSV Log Files

StationAlarm writes detailed CSV logs to the device storage:

```bash
# List log files
adb shell ls /sdcard/Download/TrainAlarmLogs/

# Pull the latest log to your laptop
adb pull /sdcard/Download/TrainAlarmLogs/ ./logs/

# View the latest log
adb shell cat /sdcard/Download/TrainAlarmLogs/TrainAlert_*.csv | head -20
```

CSV columns: `timestamp, eventType, stationId, latitude, longitude, distanceKm, batteryPercent, deviceModel, manufacturer, androidVersion, extra`

---

## 7. Troubleshooting

### 7.1 Geofence Never Fires

**Symptom**: No `GeofenceBR` log appears after moving into the geofence radius.

**Possible causes:**
- **Used `am force-stop` instead of `am kill`**: Force-stop removes geofences. Re-open the app and set the alarm again.
- **Google Play Services issue**: Restart Play Services: `adb shell am force-stop com.google.android.gms` (it auto-restarts).
- **Mock location not working**: Check Developer Options -> "Select mock location app" is set correctly.
- **Real GPS overriding mocks**: Test indoors or enable airplane mode (keep WiFi on if needed for ADB over WiFi). On physical device with USB ADB, airplane mode is fine.
- **Geofence not registered**: Check with `adb shell dumpsys activity service com.google.android.gms | grep stationalarm`.

### 7.2 BroadcastReceiver Fires But Service Doesn't Start

**Symptom**: See `GeofenceBR: Geofence triggered` but no `SERVICE_GEOFENCE_RECEIVED`.

**Possible causes:**
- **Android 12+ foreground service restrictions**: Starting a foreground service from a BroadcastReceiver is only allowed for certain broadcast types. Geofence PendingIntent broadcasts are exempt — but some OEMs add extra restrictions.
- **OEM battery optimization killed the service start**: Check OEM-specific settings (see Section 8).

### 7.3 Lockito Route Stops Mid-Test

**Symptom**: Mock locations stop being injected (GPS returns to real position or stops updating).

**Possible causes:**
- **OEM killed Lockito**: Whitelist Lockito from battery optimization (see Section 4.2D).
- **Lockito not set as mock location app**: Re-check Developer Options.
- **Screen lock interrupted Lockito**: Ensure Lockito was started BEFORE locking the screen.

### 7.4 Doze Not Actually Active

**Symptom**: `mState` shows `ACTIVE` even after `force-idle`.

**Possible causes:**
- **Screen is on**: Turning the screen on exits Doze immediately.
- **Battery not "unplugged"**: Run `adb shell dumpsys battery unplug` first.
- **Motion detected**: Some devices exit Doze on movement (shouldn't matter if you used `force-idle`).

### 7.5 Service Starts But No GPS Updates

**Symptom**: Service is running (visible in `dumpsys activity services`) but no `LOCATION_POLL` events.

**Possible causes:**
- **Location permission revoked**: Check `adb shell dumpsys package com.omama.stationalarm | grep -i permission`.
- **Mock location overriding FusedLocationProvider**: The mock coordinates from Lockito should be picked up by FusedLocationProvider, but some devices have quirks.
- **Watchdog will catch it**: The 30-second watchdog timer will detect GPS stalls and restart polling. Wait for `WATCHDOG_TRIGGERED`.

---

## 8. OEM-Specific Battery Killers

The biggest real-world threat to StationAlarm isn't Doze — it's OEM battery "optimization" that goes beyond stock Android. These OEM customizations can kill background services, remove geofences, and prevent the app from restarting.

### Reference: dontkillmyapp.com

Visit [dontkillmyapp.com](https://dontkillmyapp.com) for device-specific instructions. Key OEMs:

### Xiaomi (MIUI)

MIUI is one of the most aggressive app killers. Settings to check:
- Settings -> Battery & Performance -> Battery Saver -> StationAlarm -> **No restrictions**
- Settings -> Apps -> Manage Apps -> StationAlarm -> **Autostart: ON**
- Security app -> Manage Apps -> StationAlarm -> **Autostart: Allow**
- Also whitelist **Lockito** the same way for testing

### Samsung (One UI)

- Settings -> Battery -> Background Usage Limits -> Never Sleeping Apps -> **Add StationAlarm**
- Settings -> Battery -> Battery Optimization -> StationAlarm -> **Don't optimize**
- Also: Settings -> Apps -> StationAlarm -> Battery -> **Unrestricted**

### OnePlus (OxygenOS / ColorOS)

- Settings -> Battery -> Battery Optimization -> StationAlarm -> **Don't optimize**
- Settings -> Apps -> StationAlarm -> Battery -> **Allow background activity**

### Oppo / Realme (ColorOS)

- Settings -> Battery -> More Settings -> Optimize Battery Use -> StationAlarm -> **Don't optimize**
- Settings -> App Management -> StationAlarm -> **Battery: Allow background running**

### Huawei (EMUI)

- Settings -> Battery -> App Launch -> StationAlarm -> **Manage Manually** -> Enable all three toggles (Auto-launch, Secondary launch, Run in background)

### Testing OEM Kill Behavior

After setting the alarm on an OEM device:
```bash
# Check if the app survives OEM optimization
# Wait 5-10 minutes with screen off, then check:
adb shell ps | grep stationalarm
adb shell dumpsys activity services com.omama.stationalarm
```

If the service is gone without the user force-stopping it, the OEM killed it. Apply the whitelist settings above and re-test.

---

## 9. Quick Reference: Copy-Paste Commands

### Emulator Quick Test (Scenario C: Kill + Doze + Geofence)

```bash
# Terminal 1: Logcat (keep open)
adb logcat -s GeofenceBR:D LocationService:D BootReceiver:D

# Terminal 2: Commands (run in order)
adb emu geo fix 72.8777 19.0760                        # Start far away (Mumbai)
# ... set alarm in app for New Delhi ...
adb shell am kill com.omama.stationalarm               # Kill app
adb shell ps | grep stationalarm                       # Verify dead
adb shell dumpsys battery unplug                        # Fake unplug
adb shell input keyevent KEYCODE_POWER                 # Screen off
adb shell dumpsys deviceidle force-idle                 # Force Doze
adb shell dumpsys deviceidle | grep mState             # Verify: IDLE
adb emu geo fix 77.22 28.65                            # Move into alert zone
# ... wait up to 2-6 min, watch Terminal 1 ...
adb shell dumpsys activity services com.omama.stationalarm  # Verify service running
adb shell dumpsys deviceidle unforce                    # Cleanup
adb shell dumpsys battery reset                         # Cleanup
adb shell input keyevent KEYCODE_POWER                 # Screen on
```

### Physical Device Quick Test (Scenario D: Lockito + Kill + Doze)

```bash
# Terminal 1: Logcat (keep open)
adb logcat -s GeofenceBR:D LocationService:D BootReceiver:D

# Terminal 2: Commands (run in order, AFTER setting up Lockito route & starting it)
adb shell am kill com.omama.stationalarm               # Kill app
adb shell ps | grep stationalarm                       # Verify dead
adb shell input keyevent KEYCODE_POWER                 # Screen off
adb shell dumpsys battery unplug                        # Fake unplug
adb shell dumpsys deviceidle force-idle                 # Force Doze
adb shell dumpsys deviceidle | grep mState             # Verify: IDLE
# ... wait for Lockito route to reach geofence, watch Terminal 1 ...
adb shell dumpsys activity services com.omama.stationalarm  # Verify service running
adb shell dumpsys deviceidle unforce                    # Cleanup
adb shell dumpsys battery reset                         # Cleanup
adb shell input keyevent KEYCODE_POWER                 # Screen on
```
