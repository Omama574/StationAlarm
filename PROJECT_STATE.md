```markdown
# StationAlarm — Project State

> **Last updated:** 2026-04-15
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

### Session: Alarm Reliability Fixes (2026-04-15)
Four user-reported critical issues fixed; `./gradlew compileDebugKotlin` clean.

1. **Alarm re-fires after dismissal** — `GeofenceBroadcastReceiver` used `StationRepository.isActive()` which returns `true` for any row, including `PAUSED`. After dismiss the row transitions to `PAUSED` while `removeGeofencesForStation()` is still in flight asynchronously on GMS; pending geofence events slipped through and called `markAlerting()`, re-triggering the alarm seconds after the user dismissed.
   - `ActiveStationDao.kt` — added `suspend fun getStatus(stationId): String?`
   - `StationRepository.kt` — added `suspend fun isArmed(stationId): Boolean` (returns false for missing rows or `PAUSED`)
   - `GeofenceBroadcastReceiver.kt` — swapped `isActive` → `isArmed`; PAUSED-window events are now dropped with a log line

2. **Toggle ON without GPS check** — `AppRoot.kt` `onToggleStation` re-armed and started the foreground service without the `isGpsEnabled()` gate the other entry points use, so the toggle would flip ON but no location updates would ever arrive. Same bug also present on `onEditStation` (re-registers geofences on save).
   - `AppRoot.kt` — added `isGpsEnabled()` gate to both `onToggleStation` (when `enabled=true`) and `onEditStation`; both now raise `showGpsDialog` on failure, matching `onStationSelected` and `onStartTrip`. All four alarm-creating/arming paths now consistently gate on GPS.

3. **"Monitoring stations…" notification + GPS wake lock lingered after alarm dismiss** — In `LocationService.syncWithDatabase()`, when both monitoring and alerting sets became empty it still called `updateForegroundNotification()` (which re-posted "Monitoring stations…" via `manager.notify`) immediately before `stopForeground(REMOVE)` — leaving a ghost notification. GPS teardown relied on step-3's else branch implicitly.
   - `LocationService.kt` (LOCKED — behavioural change required + scoped to this bug) — full-teardown branch now explicitly calls `stopLocationUpdates()` (releases fused client + GPS wake lock via `ServiceWakeLocks.releaseGps()`), then `stopForeground(STOP_FOREGROUND_REMOVE)`, then `notifications.cancelForeground()`, then `stopSelf()`. The re-notify path is skipped entirely when shutting down. GPS is still acquired freely by the map/config screens (they use FusedLocation independently of `LocationService`), so the map, search, and radius-picker flows are unaffected — GPS is only released when there are zero armed stations.
   - `ServiceNotifications.kt` — added `cancelForeground()` helper (`manager.cancel(FOREGROUND_NOTIFICATION_ID)`) since `stopForeground(REMOVE)` alone sometimes races with a prior `notifyForeground` call and leaves the notification visible.

4. **Proximity progress bar jumped between polls and looked "full" on long trips** — Bar was computed against a fixed `alertDistance + 60 km` window, so a 200 km trip looked nearly full from the start and never moved. It also snapped instantly on every poll (10s / 30s / 5m intervals looked like teleport jumps).
   - `HomeScreen.kt` `StationCard` — bar now anchored to the **largest observed distance per `stationId`** (via `remember(station.stationId) { mutableStateOf<Double?>(null) }` + a `LaunchedEffect` that grows but never shrinks the anchor); progress fills linearly as device closes in: `(anchor − current) / (anchor − alertDistance)`. Wrapped in `animateFloatAsState(tween 900ms, LinearEasing)` so the bar slides smoothly between poll intervals instead of jumping.

**User-flow sanity pass after touching LOCKED `LocationService.kt`:**
- Fresh alarm add → MONITORING → `isArmed=true` → receiver passes → unchanged behaviour.
- Alarm fires → dismiss → PAUSED: sync sees empty sets → full teardown → GPS released, foreground notification gone, service stops.
- Re-toggling ON re-arms and restarts service (after GPS check passes).
- Toggle ON with GPS off: dialog prompts, no ghost "armed" state.
- Multi-alarm case where one fires and another is still MONITORING: sets non-empty → `updateForegroundNotification()` still runs → teardown only on full empty. Correct.
- Boot restore: `BootRestoreWorker` resets `ALERTING → MONITORING`; `isArmed` passes; unchanged.

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

### Session: Legal Pages Authored (2026-04-14)
- New standalone repo at `C:\Users\omama\AndroidStudioProjects\stationalarm-legal\` (not yet on GitHub).
- `privacy.html` — full Privacy Policy with actual data-flow table (GPS stays on device; only typed search queries leave; Firebase crash/analytics retention disclosed; third-party list: LocationIQ, Photon, Firebase, Google Play Services, OSM, Cloudflare).
- `terms.html` — Terms of Use with prominent "best-effort, not safety-critical; always have a backup" callout; governing law = India.
- `index.html` — landing page with links to both.
- `style.css` — single stylesheet, light/dark via `prefers-color-scheme`.
- Two placeholder tokens to find-replace before first production deploy: `<dedicated-email-placeholder>` (user will create dedicated Gmail) and `<effective-date-placeholder>`.
- Initial commit `4754390` in the new local repo.
- `AboutScreen.kt` — dropped the `// Placeholder URLs` comment; URL constants unchanged (already match `stationalarm-legal.pages.dev`).
- **Next steps for the user** (manual, outside Claude):
  1. Create a public GitHub repo `stationalarm-legal`, push `main` to it.
  2. Cloudflare Pages → Create project → Connect to Git → pick `stationalarm-legal` → framework preset None, build command blank, build output `/` → deploy.
  3. Verify `stationalarm-legal.pages.dev/privacy` and `/terms` load.
  4. Create the dedicated Gmail for app contact; find-replace both placeholder tokens in all three HTML files; commit + push.

### Session: Production Readiness Pass 1 (commit `72a360d`, 2026-04-14)
Implemented all "TO-DO IMMEDIATELY" items from `plans/production-readiness.md`:
- **Signing (#1)**: `app/build.gradle.kts` signing config loaded from optional `keystore.properties` (gitignored along with `*.keystore`/`*.jks`). Missing file → unsigned release so debug builds still work.
- **Log hygiene (#3)**: `RetrofitClient.kt` only registers `HttpLoggingInterceptor` when `BuildConfig.DEBUG` — no request/response bodies in Logcat on release.
- **Room (#4)**: `StationDatabase.kt` now calls `.fallbackToDestructiveMigrationFrom(1)` — legacy v1 dev DBs wipe instead of crashing.
- **MediaPlayer error listener (#6, LOCKED)**: `AlarmAudioController.startPlayer` sets `setOnErrorListener` with `isDefaultAttempt` flag; async prepare/playback errors now fall back to system default URI or flip `isAlarmRinging=false` instead of silent-stuck state.
- **ANR / cached URI (#7, LOCKED)**: `LocationService.observeAlarmSoundUri()` collects `UserPreferences.alarmSoundUriFlow` in `onCreate()` into `@Volatile cachedAlarmSoundUri`. `fireAlert()` reads the var — no more `runBlocking` on Main dispatcher.
- **URI revalidation (#12, LOCKED)**: new `resolveValidatedAlarmUri()` probes `contentResolver.openInputStream(uri)` before passing to audio; revoked SAF permissions / deleted files fall back to default.
- **Geofence result propagation (#8+9, LOCKED)**: `GeofenceManager.addGeofencesForStation` returns `Result<Unit>` with typed `GeofenceRegistrationException` (`MissingPermission`, `StationNotFound`, `MaxStationsReached`, `RegistrationFailed`). `StationRepository`:
  - Pre-checks max cap before DB insert
  - Rolls back `active_stations` row on failure
  - Emits user-facing strings via new `errorEvents: SharedFlow<String>`
  - `rearmStation()` reverts to PAUSED if re-registration fails (toggle can't lie)
  - New suspend `reRegisterAllGeofencesNow(): Boolean` for use by `BootRestoreWorker`
- **Notification permission rationale (#10)**: `AppRoot.kt` tracks `POST_NOTIFICATIONS` separately on Android 13+, shows new `NotificationPermissionDialog` on denial, deep-links to `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. Re-checked on `ON_RESUME` so returning from Settings auto-dismisses.
- **Snackbar error wiring**: `AppRoot.kt` collects `viewModel.errorEvents` and surfaces via existing `snackbarHostState`.
- **BootReceiver via WorkManager (#13)**: New `receiver/BootRestoreWorker.kt` (CoroutineWorker). `BootReceiver` enqueues `OneTimeWorkRequest` with `ExistingWorkPolicy.KEEP` + `BackoffPolicy.EXPONENTIAL` (10s base). Worker resets stale ALERTING→MONITORING, calls `reRegisterAllGeofencesNow()`, starts `LocationService`; returns `Result.retry()` on failure for WorkManager backoff.
- **Compose BOM (#14)**: `2024.09.00` → `2025.02.00`.
- **versionCode (#15)**: `app/build.gradle.kts` — `versionCode = gitCommitCount()` (`git rev-list --count HEAD`, falls back to 1 if git unavailable); `versionName = "1.0.$appVersionCode"`.
- **Build verification**: `./gradlew compileDebugKotlin` + `./gradlew assembleRelease` both clean. Unsigned release APK produced (expected — no keystore.properties yet).

New dep: `androidx.work:work-runtime-ktx:2.9.1` for the BootRestoreWorker.

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
- **Create release keystore:** Generate `.keystore`, write `keystore.properties` (storeFile/storePassword/keyAlias/keyPassword) to project root, confirm `./gradlew assembleRelease` produces a signed AAB
- **Deploy legal pages:** Push `stationalarm-legal` repo to GitHub, connect Cloudflare Pages, verify `/privacy` + `/terms` load. Find-replace `<dedicated-email-placeholder>` and `<effective-date-placeholder>` with real values.
- **Battery optimization onboarding (#5 deferred):** First-launch dialog + deep-link to `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (prevents "missed alarm" reviews on MIUI/OneUI)
- **Localization (#11 deferred):** Hardcoded strings → `strings.xml`
- **On-device verification (drawer):** Hamburger opens drawer; Settings/About navigate correctly; back returns to main; theme/unit/sound persist across restarts
- **On-device verification (alarm sound):** System ringtone picker selects and plays; local audio file via SAF persists URI across reboot; custom sound fires in `fireAlert()`; revoked-permission URI falls back to default
- **On-device verification (alarms tab):** Pause/rearm toggle; proximity bar updates; Edit/Map/Delete buttons function; empty-state CTA
- **On-device verification (snackbars):** Add 11th alarm → "max 10" snackbar; deny POST_NOTIFICATIONS on Android 13 → rationale dialog appears
- **On-device verification (boot restore):** Set alarm → reboot → confirm `BootRestoreWorker` runs and geofences are re-registered (check Logger output for `BOOT_RESTORE`)
- **Doze Mode Recovery:** Lock screen off charger 3+ hours, simulate entering geofence, verify `ServiceWakeLocks` bypasses deep sleep correctly
- **High-Velocity Polling:** Mock location at 150–250 km/h toward active station, verify adaptive tracker fires before passing threshold
- **Failover Chaos Drill:** Misconfigure Remote Config URL to force 502, verify Photon cleanly takes over

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