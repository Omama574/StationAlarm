```markdown
# StationAlarm — Project State

> **Last updated:** 2026-04-14
> Update this file at the end of every session with what changed and what's next.

---

## What This App Does

Android app that alerts the user when approaching a railway station or any custom-saved place. Designed to work reliably at high train speeds (100–300 km/h) by defeating Android doze/batching with layered geofences and adaptive polling.

---

## Tech Stack

| Layer | Tech |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + AndroidView (for osmdroid) |
| Map | osmdroid 6.1.20 + OpenStreetMap (MAPNIK tiles) |
| Geocoding primary | LocationIQ via Cloudflare Worker proxy |
| Geocoding fallback | Photon by Komoot (direct from device) |
| Database | Room SQLite v3 |
| Backend config | Firebase Remote Config (backend URL), Crashlytics |
| Preferences | DataStore Preferences (theme, distance unit, alarm sound URI) |
| Core alarm engine | LocationService + GeofenceManager (foreground service) |

---

## Architecture

### Search Flow (3-tier)
```
User types in MapSearchScreen
    │
    ├─ Tier 1: StationData (offline JSON) — Indian railway stations
    │          Instant, zero API calls. Returns if any match found.
    │
    ├─ Tier 2: LocationIQ via Cloudflare Worker
    │          Primary live geocoding. 500ms debounce.
    │          Worker URL controlled by Firebase Remote Config.
    │
    └─ Tier 3: Photon by Komoot (direct from device)
               Fallback. Called DIRECTLY (not via Worker) to keep
               per-user IP quota instead of pooled server quota.
```

### Alarm Flow
```
Station selected → StationConfigBottomSheet → radius/sound/vibrate config
    → StationRepository.addActiveStation()
    → GeofenceManager.setupGeofencesForStation()
        → 6 geofences per station (Outer/Mid/Inner × Entry/Exit)
    → LocationService (foreground) polls GPS at adaptive intervals
    → GeofenceBroadcastReceiver fires → AlarmActivity shown
    → dismissStation() → removeGeofencesForStation() cleans up
```

---

## Cloudflare Worker

- **URL:** `https://stationalarm-geo.mohammedomama2005.workers.dev/`
- **Endpoints:** `/autocomplete` → LocationIQ v1/autocomplete, `/reverse` → LocationIQ v1/reverse, `/search` → LocationIQ v1/search (future)
- **Caching:** Autocomplete 24h, reverse 1h (Cloudflare edge cache)
- **Auth:** API key injected server-side via `env.LOCATIONIQ_KEY` — never in APK
- **URL is dynamic:** Firebase Remote Config key `geocoding_backend_url` overrides at runtime

---

## Database Schema (Room v3)

### `saved_places` table (`SavedPlaceEntity`)
`id, name, lat, lon, radiusKm, notify, vibrate, sound, notes, createdAt`

### `active_stations` table (`ActiveStationEntity`)
Tracks currently active alarms.

---

## Graveyard — Rejected Decisions (Do Not Revisit Without Strong Reason)

| Decision | What was rejected | Why |
|---|---|---|
| Mapbox geocoding | Using Mapbox for search | TOS violation — geocoding results cannot be displayed on non-Mapbox maps |
| Rotating API keys | Multiple LocationIQ keys to beat rate limits | TOS risk |
| NaPTAN / EU GTFS / AU feeds | Static station data for Europe/Australia | Scope too large, maintenance burden, deferred to Phase 4 |
| Nominatim direct | As primary geocoder | Rate limits too tight for production use |
| GADM offline boundaries | Point-in-polygon city tagging | Overkill for current scope |
| Photon via Worker | Routing Photon through Cloudflare | Destroys per-user IP quota, turns it into pooled server quota |

---

## Session Log

### Session: Production Setup & Security Audit (2026-04-10)
- Firebase integration: connected to real Firebase instance, `google-services.json` secured via `.gitignore`
- Security audit: `git ls-files` + codebase grep — zero API keys, tracking IDs, or keystores exposed
- Remote Config: geocoding proxy base URL fetched from Firebase via `geocoding_backend_url`

### Session: Modularity Refactor (2026-04-07)
- `service/LocationService.kt` slimmed 819 → ~430 lines
- Extracted into same package: `AlarmAudioController.kt`, `ServiceWakeLocks.kt`, `ServiceNotifications.kt`
- UI extractions: `OsmMapView.kt` out of `MapSearchScreen.kt`; `AppRoot.kt` + `AppNavigation` out of `MainActivity.kt`
- `MainActivity.kt` now ~70 lines
- Fixed broken `MapTileApproximater` package reference from previous session
- `./gradlew compileDebugKotlin` passes clean

### Session: Codebase Cleanup (commit `ded5b16`)
- Removed `removeAllGeofences()` from `GeofenceManager.kt`
- Removed dead `updateFavoritePlace()` + `SavedPlaceDao.update()` chain
- Removed `flushAndClose()` from `Logger.kt` and `GpsLogger.kt`
- Removed unused template colors from `Color.kt`
- Removed `dynamicColor` param from `StationAlarmTheme()`
- Renamed `MapboxGeocodingService.kt` → `LocationIqService.kt`
- Renamed `MapboxModels.kt` → `GeocodingModels.kt`
- Fixed `GpsLogger` hardcoded `"IST"` timezone → `TimeZone.getDefault()`
- Fixed `LogEntry.toCsvLine()` — proper CSV escaping
- Added OkHttp timeouts: 10s connect, 15s read
- Fixed `HomeScreen` search: synchronous `remember(query)` → `LaunchedEffect` 300ms debounce
- Fixed `AlarmActivity`: forced dark theme, hardcoded colors → MaterialTheme

### Session: LocationIQ + Photon Optimization (commit `d491904`)
- LocationIQ autocomplete: `limit` 10→5, added `normalizecity=1`, added `layers` filter
- LocationIQ reverse: added `normalizeaddress=1`
- Photon: `limit` 10→5, `zoom=10`, `location_bias_scale=0.5`, `osm_tag=!boundary`, `layer` filter, `radius=0.5` on reverse
- `GeocodingModels.kt`: expanded address fields, structured subtitle logic for both providers
- Cloudflare Worker: added try/catch (502 on LocationIQ failure), CORS on all responses, `/search` endpoint

### Session: Navigation Drawer + Settings + About (commit `e555076`, 2026-04-14)
- Added `ModalNavigationDrawer` with hamburger `TopAppBar` wrapping all screens in `AppRoot.kt`
- Screen routing via `enum class Screen { Main, Settings, About }` — no Jetpack Navigation, consistent with tab pattern
- `data/UserPreferences.kt` — DataStore singleton with flows and suspend setters for: `theme_mode`, `distance_unit`, `custom_alarm_sound_uri`; initialized in `StationAlarmApplication.onCreate()`
- `util/DistanceUnit.kt` — `KM`/`MILES` enum, `LocalDistanceUnit` CompositionLocal, `formatDistance()` helper; provided at `MainActivity` level; distances displayed in correct unit across `HomeScreen`, `StationConfigBottomSheet`, `MapSearchScreen`
- `ui/screens/SettingsScreen.kt` — distance unit (`SingleChoiceSegmentedButtonRow`), theme (radio buttons), alarm sound picker (system ringtones via `RingtoneManager.ACTION_RINGTONE_PICKER` + local files via SAF `OpenDocument`; `takePersistableUriPermission` for persistence), debug log share buttons (moved from HomeScreen header)
- `ui/screens/AboutScreen.kt` — version, Privacy Policy / Terms of Use (placeholder URLs), Open Source Licenses via `OssLicensesMenuActivity`, Rate Us + Share App drawer items
- `service/AlarmAudioController.kt` (LOCKED) — added `playAlarmSound(customUri: Uri?)` overload; no-arg version delegates to it with `null`; falls back to default system alarm URI on failure
- `service/LocationService.kt` (LOCKED) — reads custom URI via `runBlocking { UserPreferences.alarmSoundUriFlow.first() }` in `fireAlert()`; try/catch falls through to `null` so default sound always fires on error
- OSS Licenses plugin wired via `buildscript { classpath(...) }` (not Gradle plugin portal); AppCompat 1.7.0 added as dependency
- `HomeScreen.kt` share-log buttons and header row removed (moved to Settings); all distance strings use `formatDistance()`

### Session: Alarms Tab Redesign (commit `0334620`, 2026-04-14)
- Replaced delete-on-dismiss with pause/rearm toggle — alarms persist after firing (`PAUSED` state instead of row deletion)
- Each station card now shows: alarm type badge, proximity progress bar (distance to station), Edit / Map / Delete action buttons, ON/OFF toggle
- Removed separate Save/Favorites flow — "Alarms" tab (renamed from "My Stations") is the single source of truth
- Added empty-state CTA when no alarms are active
- `StationRepository.kt` — added `pauseStation()` / `rearmStation()`, updated `dismissStation()` to set PAUSED rather than delete
- `StationViewModel.kt` — exposed new toggle actions; proximity distance observable added
- `MapSearchScreen.kt` — minor integration for "add alarm" CTA from empty state

### Session: Geocoding Layer Filter Fix (commit `314e8ca`, 2026-04-13)
- Removed `layers`/`layer` query params from `LocationIqService.kt` and `PhotonService.kt`
- Root cause: allowlist filters excluded all `amenity`/POI result types (bus stands, railway stations, airports); e.g. "Vellore New Bus Stand" returned zero results because `class:amenity` is not an administrative layer
- Both providers now return all result types

### Session: Testing Guide Rewrite (commit `811599d`, 2026-04-12)
- Rewrote `TESTING_GUIDE.md` — removed all emulator content
- Added deep step-by-step instructions for 7 test scenarios on physical devices via USB + Lockito
- Includes GPS verification commands, terminal setup, doze-state validation, copy-paste command blocks

### Session: OSM Tile Loading Speed (commit `bd8e230`)
- `tileDownloadThreads = 4`, `tileFileSystemThreads = 4`, `tileDownloadMaxQueueSize = 60`
- `isTilesScaledToDpi = true` — reduces tile count ~2-4x on HDPI
- Added `MapTileApproximater` overlay — eliminates blank grey squares while tiles load

---

## Pending

### Must Do Before Play Store / Field Trust
- **On-device verification (drawer):** Hamburger opens drawer; Settings/About navigate correctly; back returns to main; theme/unit/sound persist across restarts
- **On-device verification (alarm sound):** System ringtone picker selects and plays; local audio file via SAF persists URI across reboot; custom sound fires in `fireAlert()`
- **On-device verification (alarms tab):** Pause/rearm toggle; proximity bar updates; Edit/Map/Delete buttons function; empty-state CTA
- **Doze Mode Recovery:** Lock screen off charger 3+ hours, simulate entering geofence, verify `ServiceWakeLocks` bypasses deep sleep correctly
- **High-Velocity Polling:** Mock location at 150–250 km/h toward active station, verify adaptive tracker fires before passing threshold
- **Failover Chaos Drill:** Misconfigure Remote Config URL to force 502, verify Photon cleanly takes over
- **Legal URLs:** Replace placeholder URLs in `AboutScreen.kt` (`PRIVACY_POLICY_URL`, `TERMS_OF_USE_URL`) with real hosted pages before Play Store submission

### Low Priority (noted, not scheduled)
- `StationConfigBottomSheet` param `initialNotes` → rename to `customReminder`
- `BootReceiver` resets `ALERTING` state too aggressively on reboot
- Hardcoded strings → `strings.xml` for localization
- Foreground-service notification text still shows hardcoded "km" (in locked `LocationService.kt` — only changes if explicitly approved)

### Future (Phase 4)
- Play Store submission
- Europe station data (NAP/GTFS)
- "Remove all alarms" feature
- Battery optimization prompt on first launch (OEM-specific deep-link to battery settings)
```