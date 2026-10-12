# 🚆 StationAlarm

**StationAlarm** is an ultra-reliable, offline-first Android location tracking application engineered specifically for long-distance train travelers. 

It solves a critical anxiety for travelers: sleeping through their destination. Its core value proposition is flawless execution—waking the user up exactly when their train approaches the station, even amidst total internet blackouts, extreme hardware deep-sleep, and aggressive OEM battery-killing algorithms.

---

## ✨ Core Features

*   **Offline-First Autonomy & Global Search:** Features a 2-tier search architecture. Instant, offline searching for Indian Railway stations via bundled JSON, and global live geocoding powered by a provider-neutral Cloudflare Worker proxy.
*   **Smart Battery Gearbox:** Dynamically shifts GPS polling intervals based on your distance to the destination (from 10-minute polling at >100km away down to 5 seconds near the station) to achieve near-zero battery drain on 15+ hour journeys.
*   **Indestructible Background Tracking:** Employs extreme redundancy to ensure the app stays alive. If killed by an aggressive OS battery saver, crossing hardware geofences will physically resurrect the app.
*   **Safety-First Audio Routing:** Dual-route audio plays alarms through both the device speaker and connected headphones/Bluetooth devices simultaneously. Automatically rebuilds routes if Bluetooth disconnects mid-alarm. Features escalating volume (5% to 100%) and customizable alarm durations.
*   **Comprehensive Localization:** Fully localized into 21 languages with a seamless in-app locale picker for a global audience.
*   **Premium "Glass" UI:** Built with 100% Jetpack Compose Material 3 featuring real-time distance updates, dynamic animated proximity bars, and live GPS telemetry on top of `osmdroid` offline-capable maps.

---

## 🛠️ Deep Technical Architecture

### 1. The GPS Gearbox & 3-Layer Reliability Engine
Android’s Location APIs often fail during long background sessions. StationAlarm uses a layered defense system:
*   **Distance-Based Gearbox:** Switches to `PRIORITY_BALANCED_POWER_ACCURACY` (cell/WiFi) when far away (>100km) to save battery, shifting to `PRIORITY_HIGH_ACCURACY` (GPS) when closer.
*   **Cold-Start Hunter:** Forces aggressive 5-second polling on startup to wake up the GPS chip and achieve a mathematically proven "First Fix".
*   **Emergency Coarse Fallback:** A self-healing watchdog dynamically arms a raw Android `LocationManager.NETWORK_PROVIDER` if Google Play Services stalls.
*   **Nuclear IPC Reset (`FlpClientGuardian`):** If the Google Play Services IPC pipe completely freezes, the app destroys the dead client in memory and builds a brand-new connection to Google from scratch.

### 2. OS Survival & Anti-Doze Tripwires
To combat aggressive OEM battery savers (Xiaomi, Samsung, Oppo):
*   **9-Tier Geofencing:** Registers 9 hardware geofences per station (including massive 100km, 150km, and 200km wake-up tripwires plus precise Inner/Mid/Outer radii). Crossing these physical thresholds causes the Android OS to resurrect the app from the dead.
*   **START_STICKY Foreground Service:** `LocationService` is exempt from standard Doze limits and auto-restarts if killed for RAM.
*   **Strict Wake Lock Discipline:** Uses `ServiceWakeLocks` to manage `PARTIAL_WAKE_LOCK`s safely, ensuring the phone stays awake exactly when needed and releases locks immediately after to prevent battery drain.
*   **Battery Optimization Onboarding:** Dedicated UX to safely prompt users to exempt the app from OEM battery restrictions.

### 3. Secure, Provider-Neutral Geocoding
*   **Zero API Keys in APK:** All geocoding runs through a Cloudflare Worker proxy. 
*   **Dynamic Backend:** The Worker URL is fully manageable via Firebase Remote Config, allowing backend provider swaps without an app update.
*   **Provider-Neutral:** The app speaks a unified 5-field contract (`id`, `name`, `formattedAddress`, `lat`, `lon`), making the backend seamlessly interchangeable between LocationIQ, Photon, or Mapbox.

---

## 🏗️ Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Kotlin |
| **UI** | Jetpack Compose (Material 3) + AndroidView |
| **Map Engine** | osmdroid 6.1.20 + OpenStreetMap (MAPNIK tiles) |
| **Geocoding** | Cloudflare Worker Proxy (Provider-Neutral) |
| **Database** | Room SQLite (v6) |
| **State & Prefs**| DataStore, Coroutines, Flow |
| **Backend & Config**| Firebase Remote Config, Crashlytics, Analytics |

---

## 📊 Diagnostics & Telemetry
StationAlarm includes a custom CSV telemetry logger (`GpsLogger.kt`) that writes highly detailed diagnostics to `Downloads/GpsThrottle_TestLog.csv`. It tracks battery percentage, FLP failures, gate decisions, satellite counts, and watchdog stall metrics to debug field failures across different devices.

---

## 🚀 Build Instructions

```bash
# Check compile only
./gradlew compileDebugKotlin

# Run unit tests
./gradlew testDebugUnitTest

# Full debug build
./gradlew assembleDebug
```

---

*StationAlarm — Never miss your stop again.*
