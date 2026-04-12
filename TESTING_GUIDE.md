# StationAlarm — Physical Device Geofence & Doze Testing Guide

Step-by-step instructions for testing geofence wake-up, Doze survival, and alarm reliability on a **real Android phone** connected via USB. No emulator steps — everything here targets OEM devices (Xiaomi, Samsung, OnePlus, etc.) where the real-world reliability battle is fought.

---

## 1. Architecture Overview (What You're Testing)

### 1.1 Geofence Layers

StationAlarm registers **6 concentric geofences** per station via Google Play Services. Each is an ENTER-only trigger with 0 ms responsiveness and no expiration.

| Layer   | Radius Formula             | Example (alertDist = 5 km) | Purpose                        |
|---------|----------------------------|----------------------------|---------------------------------|
| level5  | alertDistanceKm + 60       | 65 km                      | Outermost early-warning ring    |
| level4  | alertDistanceKm + 40       | 45 km                      | Escalation ring                 |
| level3  | alertDistanceKm + 30       | 35 km                      | Mid-range ring                  |
| level2  | alertDistanceKm + 5        | 10 km                      | Close approach ring             |
| level1  | alertDistanceKm            | 5 km                       | Inner ring (matches alert dist) |
| alert   | alertDistanceKm (min 100 m)| 5 km                       | Alarm ring (fires the alert)    |

`alertDistanceKm` is user-configurable from 3.0 to 20.0 km. Geofence IDs: `geofence_<stationId>_<layer>`.

### 1.2 Wake-Up Chain

When the device enters a geofence — even if the app is killed:

```
Google Play Services detects geofence ENTER
  → PendingIntent fires (survives process death)
  → GeofenceBroadcastReceiver.onReceive()
      - goAsync() for background processing
      - Parses geofence requestId → stationId + layer
      - Verifies station is still active in Room DB
      - Logs: GEOFENCE_TRIGGERED
  → ContextCompat.startForegroundService(ACTION_GEOFENCE_TRIGGERED or ACTION_ALERT_GEOFENCE_TRIGGERED)
  → LocationService.onStartCommand()
      - Immediately calls startForeground()
      - Non-alert layers → restartLocationUpdates() → forces immediate GPS poll
      - Alert layer → markAlerting() → syncWithDatabase() → fireAlert()
          → AlarmAudioController plays alarm sound + vibration
          → ServiceNotifications shows full-screen intent
          → AlarmActivity appears (even on lock screen)
          → ServiceWakeLocks acquires PARTIAL_WAKE_LOCK (10 min cap)
```

### 1.3 Log Events

Written to both Logcat and CSV (`Downloads/TrainAlarmLogs/`):

| Event                              | Logcat Tag     | Meaning                                      |
|------------------------------------|----------------|-----------------------------------------------|
| `GEOFENCE_TRIGGERED`               | GeofenceBR     | Geofence PendingIntent received               |
| `SERVICE_GEOFENCE_RECEIVED`        | Logger CSV     | Service received the non-alert geofence intent |
| `SERVICE_ALERT_GEOFENCE_RECEIVED`  | Logger CSV     | Service received the alert-layer intent        |
| `GPS_STARTED`                      | Logger CSV     | GPS polling started / restarted                |
| `WAKELOCK_ACQUIRED`                | Logger CSV     | GpsWakeLock acquired                           |
| `MODE_CHANGED`                     | Logger CSV     | Polling interval changed                       |
| `LOCATION_POLL`                    | Logger CSV     | GPS fix received, distance calculated          |
| `ALERT_FIRED`                      | Logger CSV     | Alarm triggered for a station                  |
| `ALARM_TIMEOUT`                    | Logger CSV     | Auto-dismissed after 5 minutes                 |
| `ALERT_DISMISSED`                  | Logger CSV     | User dismissed the alarm                       |
| `WATCHDOG_TRIGGERED`               | Logger CSV     | GPS stalled, watchdog restarted it             |
| `SYSTEM_BOOTED`                    | BootReceiver   | Device rebooted, restoring alarms              |
| `BOOT_RESTORE`                     | BootReceiver   | Geofences re-registered after boot             |

### 1.4 Polling Intervals (Speed-Adaptive)

| Distance to Station | Polling Interval |
|----------------------|------------------|
| ≤ 5 km               | 10 seconds       |
| 5–15 km              | 30 seconds       |
| 15–30 km             | 1 minute         |
| 30–60 km             | 5 minutes        |
| > 60 km              | 10 minutes       |

---

## 2. Critical Concepts

### 2.1 `am kill` vs `am force-stop`

| Command | What It Does | Geofences Survive? | Use For Testing? |
|---------|-------------|--------------------|--------------------|
| `adb shell am kill com.omama.stationalarm` | Sends SIGKILL. PendingIntents and geofences remain registered with Play Services. | **YES** | **YES** — simulates OEM / memory-pressure kill |
| `adb shell am force-stop com.omama.stationalarm` | Kills process AND removes all PendingIntents, unregisters geofences, clears alarms. | **NO** | **NO** — simulates user Settings → Force Stop |

**Always use `am kill` for testing. Never use `am force-stop`.**

### 2.2 The Doze + Screen-On Paradox

Android Doze has a critical constraint: **turning the screen ON immediately exits Doze.** Even if you forced Doze via terminal, the moment the screen turns on the `DeviceIdleController` receives `SCREEN_ON` and transitions back to `ACTIVE`. This means you cannot open a FakeGPS app to change location while in Doze — the test is invalidated.

**Solution**: Use **Lockito** — a mock location app that supports pre-planned timed routes running as a **background service**. You set up the route and start it while the screen is ON, then lock the screen and force Doze. Lockito keeps feeding GPS coordinates with the screen off.

### 2.3 USB Charging Blocks Doze

A device that is charging will NOT enter Doze. Since your phone is connected via USB for ADB, you must fake the battery state:

```bash
adb shell dumpsys battery unplug    # OS thinks it's on battery
adb shell dumpsys battery reset     # Restore real state when done
```

### 2.4 Foreground Service vs Doze

If your app has an active foreground service, the system does NOT consider it "idle". To test wake-from-killed-state, ensure the `LocationService` is NOT running before forcing Doze — kill the app first.

---

## 3. Setup — One-Time Preparation

### 3.1 Where to Run Commands

All `adb` commands in this guide are run from a **terminal on your laptop** — not on the phone. Any of these work:

| Terminal | How to Open |
|----------|-------------|
| **Android Studio Terminal** | Bottom bar → "Terminal" tab. ADB is auto-detected. **Recommended.** |
| **Windows Terminal / PowerShell** | Start menu → "Terminal". ADB must be in your PATH (see below). |
| **Git Bash** | Right-click folder → "Git Bash Here". ADB must be in PATH. |
| **VS Code Terminal** | Ctrl+` in VS Code. ADB must be in PATH. |

If `adb` is not found in non-Android-Studio terminals, add it to PATH:

```bash
# Find where Android SDK is installed (usually one of these):
# C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools
# Add to PATH permanently via: System Properties → Environment Variables → Path → Add the above

# Or set temporarily in the current terminal session:
export PATH="$PATH:/c/Users/omama/AppData/Local/Android/Sdk/platform-tools"
```

### 3.2 Multiple Terminals

You will need **two terminal windows open simultaneously**:

| Window | Purpose |
|--------|---------|
| **Terminal 1 — Logcat** | Streams live logs from the phone. Leave this running the entire test. |
| **Terminal 2 — Commands** | Where you type ADB commands (kill app, force doze, verify GPS, etc.). |

In Android Studio, click the `+` icon next to the Terminal tab to open a second terminal. In Windows Terminal, use Ctrl+Shift+T for a new tab.

### 3.3 Phone Preparation

**A. Enable Developer Options:**
Settings → About Phone → Tap "Build Number" 7 times until it says "You are now a developer".

**B. Enable USB Debugging:**
Settings → Developer Options → USB Debugging → ON. When you plug in the USB cable, tap "Allow" on the phone's debug prompt.

**C. Verify ADB sees your device:**

```bash
adb devices
```

Expected output:

```
List of devices attached
XXXXXXXX    device
```

If it shows `unauthorized`, re-check the debug prompt on the phone. If it shows nothing, try a different USB cable or port.

**D. Install Lockito from Play Store** (free).

**E. Set Lockito as mock location provider:**
Settings → Developer Options → "Select mock location app" → **Lockito**.

> On some OEMs this is under: Developer Options → "Mock location app" or "Allow mock locations".

**F. Whitelist Lockito from battery optimization** (CRITICAL — if the OEM kills Lockito mid-test, mock GPS stops):

| OEM | Path |
|-----|------|
| **Stock Android** | Settings → Battery → Battery Optimization → All Apps → Lockito → Don't optimize |
| **Xiaomi** | Settings → Battery & Performance → App Battery Saver → Lockito → No restrictions. Also: Security → Manage Apps → Lockito → Autostart: ON |
| **Samsung** | Settings → Battery → Background Usage Limits → Never sleeping apps → Add Lockito |
| **OnePlus** | Settings → Battery → Battery Optimization → Lockito → Don't optimize |
| **Oppo/Realme** | Settings → Battery → More Settings → Optimize Battery Use → Lockito → Don't optimize |

### 3.4 Choose Test Coordinates

Pick a station to test. You need two GPS points:

| Point | What | Example (New Delhi / NDLS) |
|-------|------|----------------------------|
| START | Far outside outermost geofence (> 65 km away) | 19.0760, 72.8777 (Mumbai) |
| END   | Inside the alert geofence | 28.6400, 77.2200 (near NDLS) |

**Tip for faster tests:** Instead of Mumbai → Delhi (1400 km), pick a start point just outside the outermost ring — e.g., ~70 km from the station. At 100 km/h that's only ~42 minutes to cross all rings.

### 3.5 Create the Lockito Route

1. Open Lockito on your phone.
2. Tap the `+` button to create a new route (or "Simulate Route" / "Itinerary" depending on Lockito version).
3. Place the **start point** on the map at your START coordinates.
4. Place the **end point** at your END coordinates (near the station).
5. You can add intermediate waypoints to follow roads if you want realism, or just leave it as a straight line.
6. Set speed: **100 km/h** (realistic train speed). For faster testing, use **300 km/h**.
7. **Save the route** — you'll reuse it across multiple test scenarios.

---

## 4. Verifying GPS & Doze State (Reference Commands)

These commands are critical for confirming that your test conditions are actually in place. Use them throughout every scenario.

### 4.1 Verify Mock GPS Is Changing (Is Lockito Actually Working?)

Before you start any scenario, confirm Lockito is injecting coordinates and they are changing over time:

```bash
# Method 1: Check the last known GPS location reported to the system
adb shell dumpsys location | grep -A 2 "last location"
```

Run this command **twice**, 10–15 seconds apart. The latitude/longitude should change between the two runs if Lockito is injecting a moving route. Example output:

```
last location=Location[gps 28.5000,77.1000 acc=5 ...]
```

Wait 10 seconds, run again:

```
last location=Location[gps 28.5200,77.1100 acc=5 ...]
```

The coordinates changed → Lockito is working.

```bash
# Method 2: Watch real-time location updates from the fused provider
adb shell dumpsys location | grep -A 5 "fused"
```

```bash
# Method 3: Check which provider is being used (should show "mock" or "gps")
adb shell dumpsys location | grep -i "mock"
```

If the provider shows `mock=true`, Lockito is active and providing coordinates.

```bash
# Method 4: Continuous monitoring — watch GPS changes live
# Run this in Terminal 2 while Lockito route is running:
adb shell "while true; do dumpsys location | grep 'last location' | head -1; sleep 5; done"
```

This prints the current GPS position every 5 seconds. You'll see the coordinates gradually changing. Press Ctrl+C to stop.

### 4.2 Verify Mock GPS Works In Doze Mode

This is the key validation — does Lockito still inject coordinates when the screen is off and the device is in Doze?

```bash
# 1. Start the Lockito route on phone (screen ON)
# 2. Confirm GPS is changing (use Method 1 above — run twice, see coords change)
# 3. Lock screen:
adb shell input keyevent KEYCODE_POWER
# 4. Fake battery unplug:
adb shell dumpsys battery unplug
# 5. Force doze:
adb shell dumpsys deviceidle force-idle
# 6. Verify doze is active:
adb shell dumpsys deviceidle | grep mState
# Expected: mState=IDLE
# 7. NOW check if GPS is still changing:
adb shell dumpsys location | grep -A 2 "last location"
# Wait 15 seconds...
adb shell dumpsys location | grep -A 2 "last location"
# If coordinates changed → Lockito is feeding GPS even in Doze ✓
# If coordinates are frozen → Lockito was killed or doze blocked it
```

> **Important:** If coordinates freeze in Doze, it means the OEM's battery optimization killed Lockito. Go back to Section 3.3F and whitelist Lockito more aggressively. On Xiaomi, you may also need: Security app → Permissions → Background pop-up → Lockito → Allow.

### 4.3 Verify Doze State

```bash
# Check current Doze state
adb shell dumpsys deviceidle | grep mState
```

| mState Value | Meaning |
|-------------|---------|
| `ACTIVE` | Normal operation — NOT in Doze |
| `IDLE_PENDING` | Transitioning to Doze |
| `SENSING` | Checking if device is stationary |
| `LOCATING` | Getting last location before idle |
| `IDLE` | **Deep Doze is ACTIVE** — this is what you want |
| `IDLE_MAINTENANCE` | Brief window for deferred work |

```bash
# Force deep Doze
adb shell dumpsys deviceidle force-idle

# Exit Doze
adb shell dumpsys deviceidle unforce
```

### 4.4 Verify App Process State

```bash
# Is the app process alive?
adb shell ps | grep stationalarm
# Empty output → process is dead (killed by OS or am kill)
# Shows a line → process is alive
```

### 4.5 Verify LocationService Is Running

```bash
adb shell dumpsys activity services com.omama.stationalarm
```

Look for:

```
* ServiceRecord{... com.omama.stationalarm/.service.LocationService}
  isForeground=true
```

If the output says "nothing found" or "No services", the service is not running.

### 4.6 Verify Geofences Are Registered

```bash
adb shell dumpsys activity service com.google.android.gms | grep -A 5 "stationalarm"
```

If this shows PendingIntent entries with `stationalarm` in them, geofences are registered with Play Services.

### 4.7 Check Battery / Charging State

```bash
adb shell dumpsys battery
```

Look for `USB powered: true/false` and `status` field. After `dumpsys battery unplug`, it should show USB powered: false.

### 4.8 Screen On/Off

```bash
# Toggle screen on/off (same command for both):
adb shell input keyevent KEYCODE_POWER

# Check if screen is on or off:
adb shell dumpsys power | grep "Display Power"
# "Display Power: state=ON" or "Display Power: state=OFF"
```

---

## 5. Test Scenarios

All 7 scenarios below are written for a physical device connected via USB with Lockito for mock GPS.

---

### Scenario 1: App Running + Geofence Enter

**What you're testing:** With the app open and running, does entering a geofence escalate the polling interval?

**Preconditions:** App is open, alarm is set, service is running.

#### Steps

**Terminal 1 — start logcat:**

```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

**On the phone:**

1. Open Lockito → start the saved route (from ~70 km away, heading toward the station at 100 km/h).
2. Open StationAlarm → set an alarm for New Delhi (NDLS) with alert distance 5 km → tap "Start Trip".

**Terminal 2 — verify everything is set up:**

```bash
# Verify the service is running
adb shell dumpsys activity services com.omama.stationalarm | head -10

# Verify geofences are registered
adb shell dumpsys activity service com.google.android.gms | grep -A 5 "stationalarm"

# Verify Lockito is feeding GPS (run twice, 10s apart — coords should change)
adb shell dumpsys location | grep -A 2 "last location"
```

**Wait for the route to reach the outermost geofence (level5 = 65 km from station).**

**Terminal 2 — monitor GPS position while waiting:**

```bash
# Watch your simulated position approach the station
adb shell "while true; do dumpsys location | grep 'last location' | head -1; sleep 10; done"
```

Press Ctrl+C once you see coordinates getting close to the station.

**Terminal 1 — watch logcat for these events (in order as you cross each ring):**

```
GeofenceBR: Geofence triggered: NDLS, layer=level5
SERVICE_GEOFENCE_RECEIVED
GPS_STARTED
MODE_CHANGED                          ← polling interval decreased
LOCATION_POLL                         ← distance getting smaller
GeofenceBR: Geofence triggered: NDLS, layer=level4
GeofenceBR: Geofence triggered: NDLS, layer=level3
MODE_CHANGED                          ← interval decreasing further
...continues through level2, level1, alert
```

**Verify polling interval changed:**

The `MODE_CHANGED` events in the CSV log show the old and new polling intervals. As you get closer, intervals should shrink: 600000 ms → 300000 ms → 60000 ms → 30000 ms → 10000 ms.

**Cleanup:** Stop the Lockito route on the phone.

---

### Scenario 2: App Killed + Geofence Enter (No Doze)

**What you're testing:** After the OS kills the app process, does the geofence PendingIntent survive and wake the app back up?

**Preconditions:** Alarm set, app killed with `am kill`, screen stays ON.

#### Steps

**Terminal 1 — start logcat:**

```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

**On the phone:**

1. Open Lockito → start the saved route (from ~70 km away toward the station).
2. Open StationAlarm → set alarm for NDLS with 5 km alert distance → "Start Trip".

**Terminal 2 — verify setup, then kill the app:**

```bash
# Verify geofences registered
adb shell dumpsys activity service com.google.android.gms | grep -A 5 "stationalarm"

# Kill the app process (NOT force-stop!)
adb shell am kill com.omama.stationalarm

# Confirm the app is dead
adb shell ps | grep stationalarm
# ← Should print NOTHING

# Confirm the service is gone too
adb shell dumpsys activity services com.omama.stationalarm | head -5
# ← Should say "(nothing)"
```

**Terminal 2 — verify Lockito is still feeding GPS:**

```bash
adb shell dumpsys location | grep -A 2 "last location"
# Wait 10 seconds...
adb shell dumpsys location | grep -A 2 "last location"
# Coordinates should be changing (Lockito survived the am kill — it's a separate app)
```

**Wait for the route to reach a geofence.**

**Terminal 1 — watch for the cold wake-up chain:**

```
GeofenceBR: Geofence triggered: NDLS, layer=level5
```

This means the PendingIntent fired even though the app was dead. Then:

```
SERVICE_GEOFENCE_RECEIVED
GPS_STARTED
WAKELOCK_ACQUIRED
LOCATION_POLL
```

**Terminal 2 — verify the service restarted from cold:**

```bash
# App process should be alive again
adb shell ps | grep stationalarm
# ← Should now show a process

# Service should be running as foreground
adb shell dumpsys activity services com.omama.stationalarm | head -10
# ← isForeground=true
```

**Cleanup:** Stop the Lockito route.

---

### Scenario 3: Deep Doze + Geofence Enter (App Still Running)

**What you're testing:** With the service running in foreground, does a geofence trigger still arrive promptly in deep Doze?

> Note: A foreground service keeps the app "not idle", but the geofence delivery itself may still be batched by Doze. This test checks if Play Services can still deliver geofence events under Doze.

#### Steps

**Terminal 1 — start logcat:**

```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

**On the phone:**

1. Open Lockito → start the route.
2. Open StationAlarm → set alarm for NDLS, 5 km → "Start Trip".

**Terminal 2 — verify setup:**

```bash
# Service running?
adb shell dumpsys activity services com.omama.stationalarm | head -10

# Lockito feeding GPS? (run twice, check coords change)
adb shell dumpsys location | grep -A 2 "last location"
```

**Terminal 2 — enter Doze (the service stays alive, we're just dozing the system):**

```bash
# Lock screen
adb shell input keyevent KEYCODE_POWER

# Verify screen is off
adb shell dumpsys power | grep "Display Power"
# Expected: state=OFF

# Fake battery unplug (required because USB cable = charging = no doze)
adb shell dumpsys battery unplug

# Force deep Doze
adb shell dumpsys deviceidle force-idle

# Verify Doze is active
adb shell dumpsys deviceidle | grep mState
# Expected: mState=IDLE
```

**Terminal 2 — verify Lockito still works in Doze:**

```bash
adb shell dumpsys location | grep -A 2 "last location"
# Wait 15 seconds...
adb shell dumpsys location | grep -A 2 "last location"
# Coordinates must be changing. If frozen → whitelist Lockito more aggressively.
```

**Wait for the route to reach a geofence. Watch Terminal 1.**

> In Doze, geofence triggers may be delayed by **2–6 minutes** because location updates are batched. Be patient.

**Terminal 1 — expected events:**

```
GeofenceBR: Geofence triggered: NDLS, layer=level5
SERVICE_GEOFENCE_RECEIVED
MODE_CHANGED
LOCATION_POLL
```

**Cleanup:**

```bash
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell input keyevent KEYCODE_POWER    # screen back on
```

Stop Lockito route on phone.

---

### Scenario 4: App Killed + Deep Doze + Geofence Enter (The Ultimate Test)

**What you're testing:** The worst case. App is killed, device is in deep Doze. Can the geofence PendingIntent wake the BroadcastReceiver and start the foreground service from a completely cold state?

#### Steps

**Terminal 1 — start logcat:**

```bash
adb logcat -s GeofenceBR:D LocationService:D BootReceiver:D StationAlarm:D
```

**On the phone:**

1. Open Lockito → start the saved route (from 70+ km away toward the station, 100 km/h).
2. Open StationAlarm → set alarm for NDLS, 5 km → "Start Trip".

**Terminal 2 — verify everything before we break it:**

```bash
# Geofences registered?
adb shell dumpsys activity service com.google.android.gms | grep -A 5 "stationalarm"

# Service running?
adb shell dumpsys activity services com.omama.stationalarm | head -10

# Lockito feeding GPS? (run twice)
adb shell dumpsys location | grep -A 2 "last location"
```

**Terminal 2 — kill app + enter Doze:**

Run these commands in order:

```bash
# Step 1: Kill the app
adb shell am kill com.omama.stationalarm

# Step 2: Verify it's dead
adb shell ps | grep stationalarm
# ← Must be empty

# Step 3: Lock the screen
adb shell input keyevent KEYCODE_POWER

# Step 4: Verify screen is off
adb shell dumpsys power | grep "Display Power"
# ← state=OFF

# Step 5: Fake battery unplug
adb shell dumpsys battery unplug

# Step 6: Force deep Doze
adb shell dumpsys deviceidle force-idle

# Step 7: Verify Doze is active
adb shell dumpsys deviceidle | grep mState
# ← mState=IDLE
```

**Terminal 2 — critical verification — is Lockito still feeding GPS in this state?**

```bash
adb shell dumpsys location | grep -A 2 "last location"
# Note the coordinates. Wait 15 seconds...
adb shell dumpsys location | grep -A 2 "last location"
# Coordinates MUST be different. If same → Lockito was killed, abort test.
```

**Now wait.** Lockito is driving your simulated position toward the station. Watch Terminal 1.

**Terminal 2 — optionally track progress:**

```bash
# Check position every 30 seconds to see if you're getting close
adb shell dumpsys location | grep -A 2 "last location"
```

**Terminal 1 — the full wake-up chain should appear (may take 2–6 min in Doze):**

```
GeofenceBR: Geofence triggered: NDLS, layer=level5
```

This is the critical moment — the PendingIntent survived process death AND Doze. Then:

```
SERVICE_GEOFENCE_RECEIVED
GPS_STARTED
WAKELOCK_ACQUIRED
LOCATION_POLL
```

As the route continues inward through rings:

```
GeofenceBR: Geofence triggered: NDLS, layer=level4
GeofenceBR: Geofence triggered: NDLS, layer=level3
MODE_CHANGED
GeofenceBR: Geofence triggered: NDLS, layer=level2
GeofenceBR: Geofence triggered: NDLS, layer=level1
GeofenceBR: Geofence triggered: NDLS, layer=alert
SERVICE_ALERT_GEOFENCE_RECEIVED
ALERT_FIRED
```

The alarm should ring from the phone speaker even with the screen off.

**Terminal 2 — verify the service is alive and running:**

```bash
# Process should be alive now (woken up by geofence)
adb shell ps | grep stationalarm

# Service should be foreground
adb shell dumpsys activity services com.omama.stationalarm | head -10
```

**Cleanup:**

```bash
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell input keyevent KEYCODE_POWER    # screen on
```

Stop the Lockito route on the phone. Dismiss the alarm in the app.

---

### Scenario 5: Alert Geofence — Full Alarm Verification

**What you're testing:** The complete alarm experience — sound, vibration, full-screen notification, AlarmActivity, and auto-dismiss timeout.

#### Steps

**Terminal 1 — start logcat:**

```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

**On the phone:**

1. Open Lockito → create a **short route** that **starts ~8 km away** from the station and **ends at the station** (inside the 5 km alert radius). Set speed 100 km/h. This route will be short (~5 min).
2. **Do NOT start the route yet.**
3. Open StationAlarm → set alarm for NDLS with 5 km alert distance → Sound ON, Vibrate ON → "Start Trip".

**Terminal 2 — verify setup:**

```bash
# Service running?
adb shell dumpsys activity services com.omama.stationalarm | head -5

# Geofences registered?
adb shell dumpsys activity service com.google.android.gms | grep -i "stationalarm" | head -10
```

**On the phone:** Now start the Lockito route.

**Terminal 2 — verify Lockito is working:**

```bash
adb shell dumpsys location | grep -A 2 "last location"
# Wait 10 seconds...
adb shell dumpsys location | grep -A 2 "last location"
# Coordinates should be changing and getting closer to the station.
```

**Wait for the route to enter the alert radius (~3–5 min).**

**Terminal 1 — watch for the alert chain:**

```
GeofenceBR: Geofence triggered: NDLS, layer=alert
SERVICE_ALERT_GEOFENCE_RECEIVED
ALERT_FIRED
WAKELOCK_ACQUIRED
```

**Verify the alarm experience (on the phone):**

- **Sound**: Alarm tone plays from the speaker.
- **Vibration**: Phone vibrates.
- **Full-screen notification**: Appears even on lock screen.
- **AlarmActivity**: If screen is on, the AlarmActivity is visible.

**Terminal 2 — verify from the command line:**

```bash
# AlarmActivity is in the foreground?
adb shell dumpsys activity activities | grep -i "AlarmActivity"

# Notification exists?
adb shell dumpsys notification | grep -A 5 "stationalarm"

# Wake lock acquired?
adb shell dumpsys power | grep -i "stationalarm"
```

**Test auto-dismiss (wait 5 minutes WITHOUT touching the phone):**

After 5 minutes, Terminal 1 should show:

```
ALARM_TIMEOUT
```

Sound and vibration stop automatically. The station is removed from active monitoring.

**Alternative — test manual dismiss:**

Instead of waiting, tap the dismiss button on AlarmActivity. Terminal 1 should show:

```
ALERT_DISMISSED
```

**Terminal 2 — verify the station was deactivated:**

```bash
adb shell dumpsys activity services com.omama.stationalarm
# If no other stations are monitored, service should stop itself.
# Logcat shows: SERVICE_STOPPING
```

---

### Scenario 6: Boot Recovery — Geofences Survive Reboot

**What you're testing:** After a full device reboot, does the BootReceiver re-register all geofences and restart the LocationService?

#### Steps

**On the phone:**

1. Open StationAlarm → set an alarm for any station → "Start Trip".

**Terminal 2 — verify alarm is active:**

```bash
adb shell dumpsys activity services com.omama.stationalarm | head -10
# Should show LocationService as isForeground=true
```

**Terminal 1 — start logcat (will disconnect and reconnect after reboot):**

```bash
adb logcat -s BootReceiver:D GeofenceBR:D LocationService:D StationAlarm:D
```

**Terminal 2 — reboot the device:**

```bash
adb reboot
```

ADB will disconnect. The terminal may show an error — that's normal.

**Terminal 2 — wait for the device to come back:**

```bash
# This blocks until the device is back online
adb wait-for-device

# Give the OS ~30 more seconds to fully boot (Play Services, receivers, etc.)
# Then check:
adb shell getprop sys.boot_completed
# ← Returns "1" when boot is complete
```

**Terminal 1 — reconnect logcat (if it didn't auto-reconnect):**

```bash
adb logcat -s BootReceiver:D GeofenceBR:D LocationService:D StationAlarm:D
```

**Terminal 1 — verify the boot recovery chain (may already be in the logcat buffer):**

```
BootReceiver: Device booted, restoring active alarms...
SYSTEM_BOOTED
BOOT_RESTORE — Restored N stations
SERVICE_INIT_REQUESTED
GPS_STARTED
```

> If you reconnected logcat late, these events may have already scrolled. Use `adb logcat -d` (dump the full buffer) to check:
>
> ```bash
> adb logcat -d | grep -E "BootReceiver|BOOT_RESTORE|SYSTEM_BOOTED"
> ```

**Terminal 2 — verify the service restarted:**

```bash
# Service running?
adb shell dumpsys activity services com.omama.stationalarm | head -10
# ← isForeground=true

# Geofences re-registered?
adb shell dumpsys activity service com.google.android.gms | grep -i "stationalarm" | head -10
```

**Terminal 2 — verify ALERTING stations reset to MONITORING:**

The BootReceiver resets stations that were ALERTING back to MONITORING (user may have rebooted after passing the station):

```bash
# Notification should say "Monitoring X stations..." not "ALERTING"
adb shell dumpsys notification | grep -A 3 "stationalarm"
```

**Terminal 2 — test that restored geofences actually fire:**

Start a Lockito route heading toward your station. Watch Terminal 1 for:

```
GeofenceBR: Geofence triggered: <stationId>, layer=level5
```

This proves geofences survived the reboot → BootReceiver → re-registration → working trigger cycle.

---

### Scenario 7: Watchdog Recovery — GPS Stall Detection

**What you're testing:** The LocationService's 30-second watchdog timer. If GPS stops providing updates for longer than `max(60 seconds, pollingInterval × 3)`, the watchdog force-restarts GPS polling.

#### Steps

**On the phone:**

1. Open Lockito → start a route that places you **~20 km from a station** (so polling interval is 30 seconds).
2. Open StationAlarm → set alarm for the station → "Start Trip".

**Terminal 1 — start logcat:**

```bash
adb logcat -s GeofenceBR:D LocationService:D StationAlarm:D
```

**Terminal 2 — verify GPS polling is working:**

```bash
# Service running?
adb shell dumpsys activity services com.omama.stationalarm | head -10

# Verify LOCATION_POLL events are arriving in Terminal 1 at regular intervals (~30s)
```

Wait until you see at least 3–4 `LOCATION_POLL` events in Terminal 1. This confirms the baseline is healthy.

**Terminal 2 — stall the GPS by enabling airplane mode:**

```bash
# Enable airplane mode (disables GPS, WiFi, cellular — mock locations may also stop)
adb shell cmd connectivity airplane-mode enable
```

> If `cmd connectivity` doesn't work on your device, use the alternative:
>
> ```bash
> adb shell settings put global airplane_mode_on 1
> adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
> ```

**Wait for the watchdog to trigger.**

The watchdog timeout is `max(60 seconds, currentPollingInterval × 3)`:

| Polling Interval | Watchdog Timeout |
|------------------|------------------|
| 10 seconds       | 60 seconds (minimum) |
| 30 seconds       | 90 seconds       |
| 1 minute         | 3 minutes        |
| 5 minutes        | 15 minutes       |
| 10 minutes       | 30 minutes       |

For this test at ~20 km (30s polling), the watchdog fires after **~90 seconds** of no GPS.

**Terminal 1 — watch for the watchdog:**

```
WATCHDOG_TRIGGERED — GPS stalled for Xs. Restarting.
GPS_STOPPED
GPS_STARTED
```

A watchdog notification also appears on the device.

**Terminal 2 — verify the watchdog notification:**

```bash
adb shell dumpsys notification | grep -A 5 "watchdog"
```

**Terminal 2 — restore GPS by disabling airplane mode:**

```bash
adb shell cmd connectivity airplane-mode disable

# Alternative:
# adb shell settings put global airplane_mode_on 0
# adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
```

**Terminal 1 — verify GPS polling resumes:**

`LOCATION_POLL` events should start appearing again within 30–60 seconds after airplane mode is off.

**Bonus — Watchdog During Doze (hardest test):**

Combine GPS stall with Doze to test the absolute worst case:

```bash
# Start with the alarm active and service polling normally
# Then run these in order from Terminal 2:

adb shell input keyevent KEYCODE_POWER         # screen off
adb shell dumpsys battery unplug               # fake unplug
adb shell dumpsys deviceidle force-idle         # force doze
adb shell dumpsys deviceidle | grep mState     # verify: IDLE
adb shell cmd connectivity airplane-mode enable # stall GPS

# Wait for watchdog timeout (check Terminal 1)...
# After seeing WATCHDOG_TRIGGERED:

adb shell cmd connectivity airplane-mode disable # restore GPS
# Wait for LOCATION_POLL to resume in Terminal 1

# Cleanup:
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell input keyevent KEYCODE_POWER         # screen on
```

---

## 6. Logcat & Diagnostics Cheat Sheet

### 6.1 Logcat Filters

```bash
# RECOMMENDED: Watch all StationAlarm events during testing
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

# Everything from the app (verbose)
adb logcat | grep stationalarm

# Dump the entire logcat buffer (useful after reboot to see events you missed)
adb logcat -d | grep -E "GeofenceBR|BootReceiver|LocationService|GEOFENCE|ALERT|GPS_|WATCHDOG"
```

### 6.2 Clear Logcat (Start Fresh)

```bash
# Clear the logcat buffer before starting a new scenario
adb logcat -c
```

### 6.3 Save Logcat to File

```bash
# Save a running logcat session to a file (useful for analysis later)
adb logcat -s GeofenceBR:D LocationService:D BootReceiver:D StationAlarm:D > test_session.log

# Save in background (press Ctrl+C to stop)
# Or use tee to see output AND save:
adb logcat -s GeofenceBR:D LocationService:D BootReceiver:D StationAlarm:D | tee test_session.log
```

### 6.4 CSV Log Files

StationAlarm writes detailed CSV logs to the device:

```bash
# List log files
adb shell ls /sdcard/Download/TrainAlarmLogs/

# Pull all logs to your laptop
adb pull /sdcard/Download/TrainAlarmLogs/ ./logs/

# View the latest log on the device
adb shell cat /sdcard/Download/TrainAlarmLogs/TrainAlert_*.csv | head -20
```

CSV columns: `timestamp, eventType, stationId, latitude, longitude, distanceKm, batteryPercent, deviceModel, manufacturer, androidVersion, extra`

---

## 7. OEM-Specific Battery Killers

The biggest real-world threat isn't Doze — it's OEM battery "optimization" that goes beyond stock Android.

### Reference: [dontkillmyapp.com](https://dontkillmyapp.com)

### Xiaomi (MIUI)

- Settings → Battery & Performance → Battery Saver → StationAlarm → **No restrictions**
- Settings → Apps → Manage Apps → StationAlarm → **Autostart: ON**
- Security app → Manage Apps → StationAlarm → **Autostart: Allow**
- Also whitelist **Lockito** the same way

### Samsung (One UI)

- Settings → Battery → Background Usage Limits → Never Sleeping Apps → **Add StationAlarm**
- Settings → Battery → Battery Optimization → StationAlarm → **Don't optimize**
- Settings → Apps → StationAlarm → Battery → **Unrestricted**

### OnePlus (OxygenOS / ColorOS)

- Settings → Battery → Battery Optimization → StationAlarm → **Don't optimize**
- Settings → Apps → StationAlarm → Battery → **Allow background activity**

### Oppo / Realme (ColorOS)

- Settings → Battery → More Settings → Optimize Battery Use → StationAlarm → **Don't optimize**
- Settings → App Management → StationAlarm → Battery → **Allow background running**

### Huawei (EMUI)

- Settings → Battery → App Launch → StationAlarm → **Manage Manually** → Enable all three toggles

### Testing OEM Kill Behavior

After setting the alarm on an OEM device:

```bash
# Wait 5-10 minutes with screen off, then check:
adb shell ps | grep stationalarm
adb shell dumpsys activity services com.omama.stationalarm
```

If the service is gone without user force-stop, the OEM killed it.

---

## 8. Troubleshooting

### Geofence Never Fires

**Symptom**: No `GeofenceBR` log appears after position enters the geofence radius.

| Possible Cause | Fix |
|----------------|-----|
| Used `am force-stop` instead of `am kill` | Re-open app, set alarm again. Geofences were destroyed. |
| Google Play Services stale | `adb shell am force-stop com.google.android.gms` (auto-restarts) |
| Mock location not set up | Developer Options → "Select mock location app" → Lockito |
| Real GPS overriding mocks | Test indoors. Enable airplane mode (doesn't affect USB ADB). |
| Geofences not registered | Run: `adb shell dumpsys activity service com.google.android.gms \| grep stationalarm` |

### BroadcastReceiver Fires But Service Doesn't Start

**Symptom**: See `GeofenceBR: Geofence triggered` but no `SERVICE_GEOFENCE_RECEIVED`.

| Possible Cause | Fix |
|----------------|-----|
| OEM battery optimization killed the service start | Apply OEM whitelist settings (Section 7) |
| Android 12+ foreground service restriction | Geofence PendingIntents should be exempt — but some OEMs add extra restrictions |

### Lockito Route Stops Mid-Test

**Symptom**: Mock GPS coordinates stop changing.

| Possible Cause | Fix |
|----------------|-----|
| OEM killed Lockito | Whitelist from battery optimization (Section 3.3F) |
| Lockito not set as mock location app | Re-check Developer Options |
| Screen lock interrupted Lockito | Ensure route was started BEFORE locking screen |

### Doze Not Actually Active

**Symptom**: `mState` shows `ACTIVE` after `force-idle`.

| Possible Cause | Fix |
|----------------|-----|
| Screen is on | Turn off: `adb shell input keyevent KEYCODE_POWER` |
| Battery not "unplugged" | Run `adb shell dumpsys battery unplug` first |
| The command failed silently | Re-run `adb shell dumpsys deviceidle force-idle` and check again |

### Service Starts But No GPS Updates

**Symptom**: Service running in `dumpsys activity services` but no `LOCATION_POLL` events.

| Possible Cause | Fix |
|----------------|-----|
| Location permission revoked | `adb shell dumpsys package com.omama.stationalarm \| grep -i permission` |
| Mock location provider conflict | Restart Lockito, re-select as mock provider |
| Watchdog will catch it | Wait ~60 seconds for `WATCHDOG_TRIGGERED` |

---

## 9. Quick Reference: Copy-Paste Command Blocks

### Pre-Test Verification Block

Run these before every scenario to confirm your test setup is correct:

```bash
# Device connected?
adb devices

# App installed?
adb shell pm list packages | grep stationalarm

# Service running?
adb shell dumpsys activity services com.omama.stationalarm | head -10

# Geofences registered?
adb shell dumpsys activity service com.google.android.gms | grep -A 5 "stationalarm"

# Lockito feeding GPS? (run twice, 10s apart)
adb shell dumpsys location | grep -A 2 "last location"

# Screen state?
adb shell dumpsys power | grep "Display Power"

# Doze state?
adb shell dumpsys deviceidle | grep mState

# Battery state?
adb shell dumpsys battery | grep -E "powered|status"
```

### Kill + Doze Block (Scenario 4)

```bash
adb shell am kill com.omama.stationalarm               # Kill app
adb shell ps | grep stationalarm                        # Verify dead
adb shell input keyevent KEYCODE_POWER                  # Screen off
adb shell dumpsys battery unplug                        # Fake unplug
adb shell dumpsys deviceidle force-idle                 # Force Doze
adb shell dumpsys deviceidle | grep mState              # Verify: IDLE
```

### Cleanup Block

```bash
adb shell dumpsys deviceidle unforce                    # Exit Doze
adb shell dumpsys battery reset                         # Restore battery
adb shell input keyevent KEYCODE_POWER                  # Screen on
```

### GPS Monitoring Loop

```bash
# Prints current GPS position every 5 seconds (Ctrl+C to stop)
adb shell "while true; do dumpsys location | grep 'last location' | head -1; sleep 5; done"
```
