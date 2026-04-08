# StationAlarm Project Context - Full Overview

## Project Goal
StationAlarm is an Android app that delivers reliable location-based alerts when the user is approaching a railway station or any custom-saved place. It is designed to work reliably even at high train speeds (100-300 km/h) by overcoming Android OS doze, batching, and wake delays.

## Core Innovation
- Layered geofences (Outer / Mid / Inner) for redundancy
- Adaptive speed-aware polling that escalates frequency as the user gets closer
- Static India railway dataset + full interactive map module for custom places

## Current Tech Stack
- Language: Kotlin
- UI: Jetpack Compose + AndroidView
- Map: osmdroid + OpenStreetMap tiles (unlimited free)
- Geocoding: LocationIQ via Cloudflare Worker (primary) + Photon by Komoot (fallback)
- Local Database: Room SQLite (version 3)
- Core Engine: LocationService (modularized into AlarmAudioController + ServiceWakeLocks + ServiceNotifications) + GeofenceManager + GeofenceBroadcastReceiver
- Static Data: StationData.kt (hardcoded Indian railway stations)
- Custom Places: SavedPlaceEntity in Room (saved from map tap or search)

## Key Architecture Decisions
- Two search modules that converge into the same Station domain model:
  1. Static India railway list (My Stations tab)
  2. Map module (MapSearchScreen) with forward/reverse geocoding + live radius circle
- The map module must integrate seamlessly with the existing StationConfigBottomSheet and core alarm engine without modifying the service layer.
- Backend proxy (Cloudflare Worker) is deployed — hides API keys and allows switching providers via Firebase Remote Config.

## Important Constraints & Decisions
- No vendor lock-in — using LocationIQ + OSM via Cloudflare Worker proxy.
- Backend proxy is live (Cloudflare Worker) — URL controlled via Firebase Remote Config.
- Photon by Komoot is the free fallback when LocationIQ rate-limits.
- All static data (stations + future NAP/GTFS for Europe) should eventually be editable from backend without forcing app updates.
- Caching and graceful fallback are required to handle rate-limit surges gracefully.

## Current Project Structure

### Service layer (`service/`) — alarm engine
- **LocationService.kt** — foreground service, GPS polling, state machine. Behaviour-locked. Delegates to three same-package helpers:
  - **AlarmAudioController.kt** — MediaPlayer + audio focus + vibrator + 5-min auto-dismiss timeout
  - **ServiceWakeLocks.kt** — alarm wake lock (10-min cap) + GPS anti-doze wake lock (4-hour cap, non-ref-counted)
  - **ServiceNotifications.kt** — channels, foreground notification, full-screen alert (with dismiss action), watchdog notification
- **GeofenceManager.kt** — sets up 6 geofences per station (Outer/Mid/Inner × Entry/Exit). Behaviour-locked.
- **receiver/GeofenceBroadcastReceiver.kt** — geofence event handler

### UI layer
- **MainActivity.kt** (~70 lines) — just `onCreate`/`onResume` + `hasAllLocationPermissions`/`isGpsEnabled` companion helpers
- **ui/AppRoot.kt** — `AppRoot` (permission flow), `AppNavigation` (tabs + pager + station-config sheet wiring), `PermissionRationaleDialog`, `GpsDisabledDialog`
- **ui/screens/HomeScreen.kt** — My Stations tab (active alarms list)
- **ui/screens/MapSearchScreen.kt** — Map tab (osmdroid + search + radius)
- **ui/screens/OsmMapView.kt** — the osmdroid `AndroidView` composable, extracted from MapSearchScreen
- **ui/screens/StationConfigBottomSheet.kt** — shared alarm config sheet (used by both tabs)
- **ui/screens/AlarmActivity.kt** — full-screen alarm UI
- **ui/screens/FavoritesBottomSheet.kt** — saved places picker (global FAB)
- **ui/viewmodel/StationViewModel.kt**, **MapSearchViewModel.kt**

### Data layer
- **repository/StationRepository.kt** — central data access; only place that touches Room + GeofenceManager
- **data/db/StationDatabase.kt** — Room v3, two tables: `saved_places`, `active_stations`
- **data/StationData.kt** — loader for offline `stations.json` (Indian railway DB)

### Network layer (`network/`)
- **LocationIqService.kt** — Retrofit interface for LocationIQ (called via Cloudflare Worker)
- **PhotonService.kt** — Retrofit interface for Photon (called direct from device, preserves per-IP quota)
- **GeocodingModels.kt** — all data models + `toSearchResult()` mappers
- **RetrofitClient.kt** (`GeocodingClient`) — OkHttp client, dynamic Worker URL via Remote Config

### Other
- **StationAlarmApplication.kt** — osmdroid config (tile cache, thread counts, DPI scaling), Firebase init, Remote Config fetch
- **util/Logger.kt** + **util/GpsLogger.kt** — event logger and GPS track CSV logger

### Behaviour-locked files (do not change behaviour without explicit approval)
`LocationService.kt`, `AlarmAudioController.kt`, `ServiceWakeLocks.kt`, `ServiceNotifications.kt`, `GeofenceManager.kt`, `GeofenceBroadcastReceiver.kt`. Pure structural refactors are fine; semantic changes are not.

## Non-Functional Goals
- Battery efficient
- Reliable at high speeds
- No visible API keys in APK
- Offline-first where possible
- Scalable to global use (India + Europe)

You are now fully briefed on the entire project. When I ask for code or changes, always respect the existing architecture, especially do not touch the core alarm engine (LocationService / GeofenceManager).

Ask me questions if anything is unclear.