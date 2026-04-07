# StationAlarm — Project State

> **Last updated:** 2026-04-07
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

### Key Files

| File | Role |
|---|---|
| `service/LocationService.kt` | Core alarm engine — **DO NOT TOUCH** |
| `geofence/GeofenceManager.kt` | Geofence setup/teardown — **DO NOT TOUCH** |
| `receiver/GeofenceBroadcastReceiver.kt` | Geofence event handler |
| `repository/StationRepository.kt` | Central data access layer |
| `ui/screens/MapSearchScreen.kt` | Full map + search UI (osmdroid) |
| `ui/viewmodel/MapSearchViewModel.kt` | Map search logic, geocoding calls |
| `ui/screens/HomeScreen.kt` | Main screen — active alarms list |
| `ui/screens/StationConfigBottomSheet.kt` | Shared alarm config sheet |
| `ui/screens/AlarmActivity.kt` | Full-screen alarm UI |
| `network/LocationIqService.kt` | Retrofit interface for LocationIQ (via Worker) |
| `network/PhotonService.kt` | Retrofit interface for Photon |
| `network/GeocodingModels.kt` | All geocoding data models + toSearchResult() |
| `network/RetrofitClient.kt` (GeocodingClient) | HTTP client, Worker URL management |
| `data/StationData.kt` | Loader for stations.json (Indian railway DB) |
| `data/db/StationDatabase.kt` | Room DB — SavedPlace + ActiveStation tables |
| `StationAlarmApplication.kt` | App init — osmdroid config, Firebase, Remote Config |
| `util/Logger.kt` | Event logger |
| `util/GpsLogger.kt` | GPS track CSV logger |

### Cloudflare Worker
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

## What Has Been Done (Session Log)

### Session: Codebase Cleanup (commit `ded5b16`)
- Removed `removeAllGeofences()` from `GeofenceManager.kt` — per-station cleanup via `removeGeofencesForStation()` is sufficient
- Removed dead `updateFavoritePlace()` + `SavedPlaceDao.update()` call chain
- Removed `flushAndClose()` from `Logger.kt` and `GpsLogger.kt` (never called)
- Removed unused template colors from `Color.kt` (Purple80, Pink80, etc.)
- Removed `dynamicColor` param from `StationAlarmTheme()` (no callers)
- Removed commented-out typography block from `Type.kt`
- Renamed `MapboxGeocodingService.kt` → `LocationIqService.kt`
- Renamed `MapboxModels.kt` → `GeocodingModels.kt`
- Fixed `GpsLogger` hardcoded `"IST"` timezone → `TimeZone.getDefault()`
- Fixed `LogEntry.toCsvLine()` — proper CSV escaping for commas/quotes/newlines
- Added OkHttp timeouts: 10s connect, 15s read (`RetrofitClient.kt`)
- Fixed `HomeScreen`: "delete this trip?" → "remove this alarm?", hardcoded colors → MaterialTheme
- Fixed `HomeScreen` search: synchronous `remember(query)` → `LaunchedEffect` with 300ms debounce
- Added `key = { it.id }` to search results LazyColumn
- Fixed `AlarmActivity`: forced dark theme, all hardcoded colors → MaterialTheme
- Updated `CLAUDE.md` to reflect current geocoding stack
- Updated `app/build.gradle.kts` comment (Mapbox → LocationIQ + Photon)

### Session: LocationIQ + Photon Optimization (commit `d491904`)

**LocationIQ autocomplete** (`LocationIqService.kt`):
- `limit` 10 → 5
- Added `normalizecity=1` — API fills `address.city` from hierarchy when absent
- Added `layers` filter — excludes postcodes, keeps useful location types
- **No `lat`/`lon` bias** — not officially supported by LocationIQ autocomplete; Anycast optimization lost via Worker anyway

**LocationIQ reverse** (`LocationIqService.kt`):
- Added `normalizeaddress=1` — guarantees `address.city` is always populated

**Photon search** (`PhotonService.kt`):
- `limit` 10 → 5
- Added `zoom=10` — city-level bias radius (~50km vs default ~100m street-level)
- Added `location_bias_scale=0.5` — stronger proximity weighting (default 0.2)
- Added `osm_tag=!boundary` — removes administrative polygon noise
- Added `layer=locality,district,city,county,state,country` — excludes house/street level
- Added `radius=0.5` on reverse — prevents distant address matches on map tap

**GeocodingModels.kt**:
- Added `address: LocationIqAddress?` field to `LocationIqAutocompleteResult`
- Expanded `LocationIqAddress` — added `neighbourhood`, `cityDistrict`, `postcode`
- Autocomplete `toSearchResult()`: subtitle now `road + suburb → city → state` (structured, not raw string)
- Reverse `toSearchResult()`: subtitle now `suburb → city → state`, name uses suburb/neighbourhood fallback
- Added `district`, `county`, `locality`, `postcode` to `PhotonProperties`
- Photon `toSearchResult()`: subtitle uses `district/locality → city/county → state`, deduplicates name from city

**Cloudflare Worker** (manual deploy by user):
- Added try/catch — 502 instead of crash when LocationIQ unreachable
- CORS headers (`Access-Control-Allow-Origin: *`) on ALL responses (was only on success)
- Added `/search` endpoint (forward geocoding, future-ready)

### Session: OSM Tile Loading Speed (commit `bd8e230`)

**StationAlarmApplication.kt**:
- `tileDownloadThreads = 4` (was 2) — 4 parallel tile downloads
- `tileFileSystemThreads = 4` (was 2) — 4 parallel cache reads
- `tileDownloadMaxQueueSize = 60` (was 40)

**MapSearchScreen.kt** (`OsmMapView` composable):
- `isTilesScaledToDpi = true` — fetches 256px tiles and scales up, reduces tile count ~2-4x on HDPI screens
- Added `MapTileApproximater` overlay at index 0 — shows blurry lower-zoom tiles as placeholders while correct tiles load; eliminates blank grey squares
- `MapEventsOverlay` shifted to index 1 (was 0)

---

## Known Pending Items

### Must Do (User action required)
- **Firebase setup:** Create Firebase project, download `google-services.json`, place in `app/`, enable Crashlytics, set Remote Config key `geocoding_backend_url` = Worker URL
- **Cloudflare Worker deploy:** Update Worker code with error handling + CORS fix + `/search` endpoint (code provided in session)

### Low Priority (noted, not scheduled)
- `LocationService.kt` is 820 lines — could split into polling/audio/notification helpers
- `StationConfigBottomSheet` param `initialNotes` → rename to `customReminder`
- `BootReceiver` resets `ALERTING` state too aggressively on reboot
- Hardcoded strings → `strings.xml` for localization

### Future (Phase 4)
- Play Store submission
- Europe station data (NAP/GTFS)
- "Remove all alarms" feature (would use a `removeAllGeofences()` variant)

---

## Non-Negotiable Rules
1. **Never touch** `LocationService.kt` or `GeofenceManager.kt` — core alarm engine
2. **No API keys in APK** — all go via Cloudflare Worker
3. **No `countrycodes` restriction** on geocoding — app is global
4. Photon is always called **directly from device** (not via Worker) to preserve per-user IP quota
5. Room DB schema changes require a migration — bump version in `StationDatabase.kt`
