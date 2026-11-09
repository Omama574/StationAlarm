
Your output would be verified with Codex

# StationAlarm — CLAUDE.md

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
do not add yourself as contirbutor while pushing to github
| Layer | Tech |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + AndroidView (osmdroid) |
| Map | osmdroid 6.1.20 + OSM MAPNIK tiles |
| Geocoding | LocationIQ via Cloudflare Worker proxy (single source) |
| Database | Room SQLite v6 |
| Backend config | Firebase Remote Config (`geocoding_backend_url`) + Crashlytics |

---

## Architecture

### Search (2-tier)
1. **StationData** — offline Indian railway JSON, instant, zero API calls
2. **LocationIQ** — via Cloudflare Worker, 500ms debounce, mapLatest cancellation

### Alarm Flow
```
Station selected → StationConfigBottomSheet → StationRepository.addActiveStation()
→ GeofenceManager: 9 ENTER-only geofences per station (8 radius levels + the alert ring)
→ LocationService: adaptive GPS polling
→ GeofenceBroadcastReceiver → AlarmActivity
→ dismissStation() → removeGeofencesForStation()
```

### Geofence request IDs
- Format: `geofence_<stationId>_<layer>`. Layers are `level1`…`level8` plus `alert` (9 per station, max 10 active stations — `GeofenceManager.MAX_ACTIVE_STATIONS`).
- `GeofenceBroadcastReceiver` splits on `_` (`limit = 3`), so a station ID must never contain `_`. Every ID that does not come from the offline railway list goes through `StationIds.sanitize` (keeps only `A-Za-z0-9-`).
- The `alert` layer calls `markAlertingSync` and starts the service with `ACTION_ALERT_GEOFENCE_TRIGGERED`; every other layer only wakes the service (`ACTION_GEOFENCE_TRIGGERED`) so it restarts GPS polling.
- Rows saved before `StationIds.sanitize` existed can still hold an `_` in the ID. The receiver mis-parses those, so only GPS polling can ring them.

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
| `network/GeocodingService.kt` | Retrofit interface — provider-neutral Worker contract |
| `network/GeocodingModels.kt` | Provider-neutral result models (`GeoSearchResult`, `GeocodingResponse`) |
| `network/RetrofitClient.kt` | OkHttp client, dynamic Worker URL |
| `data/StationData.kt` | Loader for stations.json |
| `data/db/StationDatabase.kt` | Room v6 — `active_stations` only (`saved_places` dropped in v6) |
| `StationAlarmApplication.kt` | App init — osmdroid config, Firebase, Remote Config |

---

## Non-Negotiable Rules

1. **No API keys in APK** — all geocoding goes via Cloudflare Worker
2. **No `countrycodes` restriction** — app is global
3. **Room DB schema changes require a migration** — bump version in `StationDatabase.kt`
4. **No Mapbox** — TOS violation (geocoding results on non-Mapbox maps)
5. **Geocoding source** — LocationIQ via the Cloudflare Worker proxy. Worker is the only place the API key lives; the app never talks to LocationIQ directly.

---

## Database Schema (Room v6)

**`active_stations`** — `stationId, alertDistanceKm, notify, vibrate, sound, customReminder, sendReminder, status, lat, lon, stationName, lastTriggeredAt`  
(`saved_places` was dropped in v6.)

---

## Build Commands

```bash
./gradlew compileDebugKotlin   # check compile only
./gradlew testDebugUnitTest    # run unit tests
./gradlew assembleDebug        # full debug build
```

## Testing

- **Stack**: JUnit4 + Robolectric 4.11.1 + `kotlinx-coroutines-test`. No mockk/mockito — use hand-written fakes (see `repository/FakeGms.java` and the `*TestSupport.kt` files).
- **Robolectric tests** use `@Config(sdk = [33], application = android.app.Application::class)`, otherwise `StationAlarmApplication.onCreate` (Firebase, `reconcileOrphans`, Remote Config) runs in the background and races the test. Copy that when adding a new one.
- **`@Ignore` means a confirmed product bug.** Every `@Ignore` under `app/src/test` marks a bug that was reproduced by a real failure (find them with `grep -rn "@Ignore" app/src/test`). Do not delete these tests to "fix" a build. When you fix the bug, remove the `@Ignore` and confirm the test passes.
- **Run a subset**: `./gradlew testDebugUnitTest --tests "<fully.qualified.Class>"`. **Force a rerun**: add `--rerun`. The XML reports in `app/build/test-results/testDebugUnitTest/` are the source of truth for counts.
- On Windows, do not run `cleanTestDebugUnitTest` from a shell whose working directory is inside `app/build/` — Gradle cannot delete the directory.
- Do not run tests in parallel Gradle builds on this machine; it has little free RAM and a shared `app/build`.

### Phone/emulator testing
- Geofences only register when Google **Location Accuracy** is on. With it off the NETWORK provider is disabled, registration fails with error `1000`, and the app rolls the alarm back.
- `adb emu geo fix <longitude> <latitude>` takes longitude first. Feed a position every ~2 s; a single fix is easy to miss.
- Reboot, Doze, force-stop, location-off and full-screen-permission-denied scenarios must be checked on a real or emulated device — unit tests cannot cover them. Bluetooth routing, OEM battery killers and real GPS need a physical phone.

## Reliability & Safety — CRITICAL
1. **Foreground Service Stability**: `LocationService` must never be stopped unless all monitoring and alerting stations are removed from the database.
2. **Battery Optimization**: The app deliberately does not declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (see `PLAY_STORE_READINESS.md`); `BatteryOptimizationHelper` deep-links to the App info page instead. Never write code that assumes the OS will keep the process alive without a foreground notification and a partial wake lock.
3. **Wake Lock Discipline**: Always use `ServiceWakeLocks` to manage `PARTIAL_WAKE_LOCK`. The alarm must hold a wake lock until the user dismisses it. Current behaviour differs in two ways: an undismissed alarm is auto-dismissed after the ring duration (default 180 s, range 60–900 s), and the alarm wake lock itself has a hard 10-minute timeout (see the `@Ignore`d tests in `ServiceWakeLocksTest`).
4. **Main Thread Safety**: `LocationService` and `AlarmAudioController` perform heavy I/O (DataStore, MediaPlayer, DB). Always use `serviceScope` or `Dispatchers.IO` to avoid ANRs.

## GPS & Location Architecture
- **Layered Provider**: Primary is Google's Fused Location Provider (FLP). Secondary is a "Coarse Fallback" using `LocationManager` (NETWORK/GPS) to handle cases where Google Play Services is throttled or killed.
- **Adaptive Polling**: GPS interval scales from 10s (within 5 km) to 10m (beyond 60 km), plus a 5s cold-start hunt for the first 90s. Never bypass `adjustPollingInterval()` logic without explicit reason.
- **FlpClientGuardian**: Internal logic that monitors FLP health and recreates the client if locations stop flowing during an active alarm.

## Logging Standards
- **Logger**: Use for general lifecycle events (e.g., `SYNC_MONITORING_ADDED`, `ALERT_FIRED`). Logs are session-based CSVs.
- **GpsLogger**: Use ONLY for low-level location telemetry (lat/lon, accuracy, speed, sats in fix). This generates a high-volume `GpsThrottle_TestLog.csv` for debugging GPS "death" or jumping.

## When Compacting

Preserve: locked file list, non-negotiable rules, current Room DB version, any
files modified this session, and any decisions made about geocoding stack.
