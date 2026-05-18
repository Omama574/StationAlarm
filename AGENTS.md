
Your output would be verified with Codex

# StationAlarm — AGENTS.md

Android app that alerts users approaching a railway station or saved place.

---

## Before You Write Anything

<important if="you are about to answer a question about code">
NEVER answer questions about specific files or behaviour without reading the
relevant file first. No speculation. If you haven't opened it, open it.
</important>

<important if="you are about to delete or remove code">
Before deleting ANYTHING — a function, import, field, or file — grep the entire
codebase for usages. If you cannot confirm zero usages, DO NOT delete it. State
what you found and ask.
</important>

<important if="you are unsure">
Stop. Name what's unclear and ask rather than guessing. Surface tradeoffs.
Present multiple interpretations when ambiguity exists.
</important>

---

## Behaviour-Locked Files — DO NOT CHANGE SEMANTICS

These files are frozen. Pure structural refactors (formatting, renaming) are OK.
Any semantic/behavioural change requires explicit approval before touching:

- `service/LocationService.kt`
- `service/AlarmAudioController.kt`
- `service/ServiceWakeLocks.kt`
- `service/ServiceNotifications.kt`
- `geofence/GeofenceManager.kt`
- `receiver/GeofenceBroadcastReceiver.kt`

---

## Tech Stack

| Layer | Tech |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + AndroidView (osmdroid) |
| Map | osmdroid 6.1.20 + OSM MAPNIK tiles |
| Geocoding primary | LocationIQ via Cloudflare Worker proxy |
| Geocoding fallback | Photon by Komoot — called DIRECTLY from device (not via Worker) |
| Database | Room SQLite v5 |
| Backend config | Firebase Remote Config (`geocoding_backend_url`) + Crashlytics |

---

## Architecture

### Search (3-tier)
1. **StationData** — offline Indian railway JSON, instant, zero API calls
2. **LocationIQ** — via Cloudflare Worker, 500ms debounce
3. **Photon** — direct from device (preserves per-user IP quota, NOT pooled)

### Alarm Flow
```
Station selected → StationConfigBottomSheet → StationRepository.addActiveStation()
→ GeofenceManager: 6 geofences per station (Outer/Mid/Inner × Entry/Exit)
→ LocationService: adaptive GPS polling
→ GeofenceBroadcastReceiver → AlarmActivity
→ dismissStation() → removeGeofencesForStation()
```

### Audio Routing (Bluetooth/Headphones)
- **Dual Routing**: Alarm rings through speaker AND active external device (BT/Wired) by default.
- **Dynamic**: `AlarmAudioController` uses `AudioDeviceCallback` to rebuild routes if Bluetooth drops during an alarm.
- **Preferences**: Global `ring_speaker_with_headphones` toggle in `UserPreferences`.

---

## Key Files

| File | Role |
|---|---|
| `service/LocationService.kt` | Core alarm engine — LOCKED |
| `service/AlarmAudioController.kt` | Audio routing, focus, and playback — LOCKED |
| `geofence/GeofenceManager.kt` | Geofence setup/teardown — LOCKED |
| `receiver/GeofenceBroadcastReceiver.kt` | Geofence event handler |
| `repository/StationRepository.kt` | Only place that touches Room + GeofenceManager |
| `ui/screens/MapSearchScreen.kt` | Map tab — osmdroid + search + radius |
| `ui/viewmodel/MapSearchViewModel.kt` | Geocoding calls, search logic |
| `ui/screens/HomeScreen.kt` | My Stations tab — active alarms list |
| `ui/screens/StationConfigBottomSheet.kt` | Shared alarm config (both tabs) |
| `ui/screens/AlarmActivity.kt` | Full-screen alarm UI |
| `data/UserPreferences.kt` | App settings (Theme, Units, Audio Routing) via DataStore |
| `network/LocationIqService.kt` | Retrofit interface — LocationIQ (via Worker) |
| `network/PhotonService.kt` | Retrofit interface — Photon (direct) |
| `network/GeocodingModels.kt` | All geocoding models + toSearchResult() mappers |
| `network/RetrofitClient.kt` | OkHttp client, dynamic Worker URL |
| `data/StationData.kt` | Loader for stations.json |
| `data/db/StationDatabase.kt` | Room v5 — saved_places + active_stations |
| `StationAlarmApplication.kt` | App init — osmdroid config, Firebase, Remote Config |

---

## Non-Negotiable Rules

1. **No API keys in APK** — all geocoding goes via Cloudflare Worker
2. **No `countrycodes` restriction** — app is global
3. **Photon is always called directly from device** — never via Worker
4. **Room DB schema changes require a migration** — bump version in `StationDatabase.kt`
5. **No Mapbox** — TOS violation (geocoding results on non-Mapbox maps)
6. **Geocoding stack is locked** — LocationIQ primary, Photon fallback, no other sources

---

## Database Schema (Room v5)

**`saved_places`** — `id, name, lat, lon, radiusKm, notify, vibrate, sound, notes, createdAt`  
**`active_stations`** — `stationId, alertDistanceKm, notify, vibrate, sound, customReminder, sendReminder, status, lat, lon, stationName`

---

## Build Commands

```bash
./gradlew compileDebugKotlin   # check compile only
./gradlew testDebugUnitTest    # run unit tests
./gradlew assembleDebug        # full debug build
```

## Reliability & Safety — CRITICAL
1. **Foreground Service Stability**: `LocationService` must never be stopped unless all monitoring and alerting stations are removed from the database.
2. **Battery Optimization**: The app requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Never write code that assumes the OS will keep the process alive without a foreground notification and a partial wake lock.
3. **Wake Lock Discipline**: Always use `ServiceWakeLocks` to manage `PARTIAL_WAKE_LOCK`. The alarm must hold a wake lock until explicitly dismissed by the user.
4. **Main Thread Safety**: `LocationService` and `AlarmAudioController` perform heavy I/O (DataStore, MediaPlayer, DB). Always use `serviceScope` or `Dispatchers.IO` to avoid ANRs.

## GPS & Location Architecture
- **Layered Provider**: Primary is Google's Fused Location Provider (FLP). Secondary is a "Coarse Fallback" using `LocationManager` (NETWORK/GPS) to handle cases where Google Play Services is throttled or killed.
- **Adaptive Polling**: GPS interval scales from 5s (near station) to 5m (far away). Never bypass `adjustPollingInterval()` logic without explicit reason.
- **FlpClientGuardian**: Internal logic that monitors FLP health and recreates the client if locations stop flowing during an active alarm.

## Logging Standards
- **Logger**: Use for general lifecycle events (e.g., `SYNC_MONITORING_ADDED`, `ALARM_STARTED`). Logs are session-based CSVs.
- **GpsLogger**: Use ONLY for low-level location telemetry (lat/lon, accuracy, speed, sats in fix). This generates a high-volume `GpsThrottle_TestLog.csv` for debugging GPS "death" or jumping.

## When Compacting

Preserve: locked file list, non-negotiable rules, current Room DB version, any
files modified this session, and any decisions made about geocoding stack.
