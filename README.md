# 🚆 StationAlarm

**StationAlarm** is an ultra-reliable, offline-first Android location tracking application engineered specifically for long-distance train travelers. 

It solves a critical anxiety for travelers: sleeping through their destination. Its core value proposition is flawless execution—waking the user up exactly when their train approaches the station, even amidst total internet blackouts, extreme hardware deep-sleep, and aggressive OEM battery-killing algorithms.

---

## ✨ Core Features

*   **Offline-First Autonomy:** Works 100% offline. Search for Indian Railway stations and track your journey entirely without an internet connection.
*   **Smart Battery Gearbox:** Dynamically shifts GPS polling intervals based on your distance to the destination. From polling every 10 minutes (at >60km away) down to every 5 seconds (at <5km away) to achieve near-zero battery drain on 15+ hour journeys.
*   **Indestructible Background Tracking:** Employs extreme redundancy to ensure the app stays alive. If killed by an aggressive OS battery saver, it will automatically resurrect itself.
*   **Premium "Glass" UI:** Built with 100% Jetpack Compose featuring real-time distance updates, dynamic backgrounds, and live GPS telemetry.
*   **Custom Audio Alarms:** Set your own custom alarm sounds via Storage Access Framework (SAF) URI persistence.

## 🛠️ Deep Technical Architecture

### 1. The GPS Gearbox
To prevent battery drain, the app does not poll the GPS constantly. It uses a dynamic "gearbox" via the `LocationService`:
*   **> 60km:** 10-minute interval using cell tower triangulation (~1500m accuracy, minimal battery).
*   **30km – 60km:** 5-minute interval.
*   **15km – 30km:** 1-minute interval.
*   **5km – 15km:** 30-second interval.
*   **< 5km:** 10-second interval using hardware GPS satellites for perfect precision on the final approach.

### 2. The 3-Layer GPS Reliability Engine
Android’s Location APIs are notorious for failing during long, continuous background sessions. StationAlarm uses a custom 3-layer defense system:
1.  **Cold-Start Hunter:** On startup, forces an aggressive 90-second GPS poll to wake up the GPS chip and achieve a mathematically proven "First Fix".
2.  **Emergency Coarse Fallback:** A watchdog dynamically arms a raw Android `LocationManager.NETWORK_PROVIDER` to fetch cell-tower updates if Google Play Services stalls.
3.  **Nuclear IPC Reset:** If the Google Play Services IPC pipe completely freezes, the app destroys the dead client in memory and builds a brand-new connection to Google from scratch.

### 3. OS Survival & Anti-Doze Tripwires
To combat aggressive OEM battery savers (Xiaomi, Samsung, Oppo):
*   **Geofences:** Silently registers 3 massive hardware geofences at **100km, 150km, and 200km**. If the app is killed, crossing these physical thresholds causes the Android OS to physically resurrect the app from the dead.
*   **START_STICKY Foreground Service:** Exempt from standard Doze limits and auto-restarts if killed for RAM.
*   **Targeted Wakelocks:** Uses `PartialWakeLocks` during critical operations to prevent the phone from sleeping mid-ring.

## 📊 Diagnostics & Telemetry
StationAlarm includes a custom CSV telemetry logger (`GpsLogger.kt`) that writes highly detailed diagnostics to `Downloads/GpsThrottle_TestLog.csv`. It tracks battery percentage, FLP failures, gate decisions, satellite counts, and watchdog stall metrics to debug field failures across different devices.

## 🏗️ Built With
*   **Kotlin**
*   **Jetpack Compose**
*   **Android Architecture Components** (Room, Coroutines, Flow)
*   **Google Play Services Location API**

---

*StationAlarm - Never miss your stop again.*
