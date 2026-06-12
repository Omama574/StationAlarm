# StationAlarm — Project State

> **Last updated:** 2026-06-12
> Update this file at the end of every session with what changed and what's next.

---

## Legal Site (`stationalarm-legal`)

**Repo:** `C:\Users\omama\AndroidStudioProjects\stationalarm-legal`  
**Hosting:** Cloudflare Pages — auto-deploys on every push to `main` (framework preset: None, no build step, output dir: `/`).  
**Live URL:** `https://stationalarm-legal.pages.dev`

### Files
| File | Purpose |
|---|---|
| `index.html` | Landing page — links to Privacy + Terms |
| `privacy.html` | Privacy Policy (served at `/privacy`) |
| `terms.html` | Terms of Use (served at `/terms`) |
| `style.css` | Shared stylesheet (light/dark via `prefers-color-scheme`) |

### Before First Publish
Two placeholders must be replaced across all three HTML files before the site is live-ready:
- `<dedicated-email-placeholder>` → real contact email
- `<effective-date-placeholder>` → publish date in `YYYY-MM-DD` format

Run a find-replace across `index.html`, `privacy.html`, and `terms.html`.

### Deploy Steps
1. Replace placeholders above.
2. `git push` to `main` — Cloudflare Pages picks it up automatically.
3. (Optional) Add a custom domain in Cloudflare Pages dashboard.

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
| Geocoding | Provider-neutral contract over Cloudflare Worker (LocationIQ behind the Worker today) |
| Database | Room SQLite v6 |
| Backend config | Firebase Remote Config — `geocoding_backend_url`, `min_supported_app_version`, `maintenance_mode`, `maintenance_message`, `feature_flags`. Crashlytics + Analytics for failure visibility. |
| Preferences | DataStore — theme, distance unit, alarm sound URI, ring_speaker_with_headphones, escalating alarm + ramp secs, alarm duration, battery-opt-dismissed, app_locale |
| Localization | `strings.xml` for every UI string + per-app locale picker via `AppCompatDelegate.setApplicationLocales` (Android 13+ also surfaces in system per-app language menu) |
| Core alarm engine | LocationService + GeofenceManager (foreground service) |

---

## Architecture

### Search Flow (2-tier)
```
User types in MapSearchScreen
    │
    ├─ Tier 1: StationData (offline JSON) — Indian railway stations
    │          Instant, zero API calls. Returns if any match found.
    │
    └─ Tier 2: LocationIQ via Cloudflare Worker
               Live geocoding for everything else. 500ms debounce,
               mapLatest cancellation. Worker URL is overridable via
               Firebase Remote Config (geocoding_backend_url).
```

### Alarm Flow
```
Station selected → StationConfigBottomSheet → radius/sound/vibrate config
    → StationRepository.addActiveStation()  [withTransaction; rolls back on geofence-reg failure]
    → GeofenceManager.setupGeofencesForStation()
        → 6 geofences per station (Outer/Mid/Inner × Entry/Exit)
    → LocationService (foreground) polls GPS at adaptive intervals
    → GeofenceBroadcastReceiver fires (gated on isArmed) → AlarmActivity shown
    → dismissStation() → removeGeofencesForStation() (suspend, retried, worker-backed)
```

---

## Cloudflare Worker

- **URL:** `https://stationalarm-geo.mohammedomama2005.workers.dev/`
- **Endpoints (provider-neutral contract — see `network/GeocodingService.kt`):**
  - `GET /search?q=...&limit=...&bias_lat=...&bias_lon=...&lang=...`
  - `GET /reverse?lat=...&lon=...&lang=...`
- **Response shape (5 fields per result):** `{ results: [{ id, name, formattedAddress, lat, lon }] }`. Reverse-with-no-address returns `{ results: [] }` and the app falls back to raw coords.
- **Caching:** Search 24h, reverse 1h (Cloudflare edge cache).
- **Auth:** API key injected server-side via `env.LOCATIONIQ_KEY` — never in the APK.
- **URL is dynamic:** Firebase Remote Config key `geocoding_backend_url` overrides at runtime. The compiled-in default in `RetrofitClient.kt` matches the live Worker, so a missed RC fetch is not a stale-URL hazard.
- **Provider swap = Worker-only change:** the app speaks the neutral shape above. To swap to e.g. self-hosted Photon, write a new translator function in the Worker — zero APK update. `bias_lat`/`bias_lon`/`lang` ride in the request today even though LocationIQ ignores them, so the contract is ready when we switch.

---

## Localization

- **Strings:** every user-facing literal lives in `res/values/strings.xml` (~238 entries grouped by screen).
- **Per-app locale:** `data/UserPreferences.appLocaleFlow` stores `"system"` (default) or a BCP 47 tag. `StationAlarmApplication.applyPersistedLocale()` reads it at process start and calls `AppCompatDelegate.setApplicationLocales(...)` BEFORE any Activity is created.
- **Picker:** Settings → Language section. Lists **22 options**: System default + 21 languages. `SettingsScreen.kt` `languageOptions` list drives the UI; `locales_config.xml` drives the Android 13+ system picker.
- **Shipped locales (21):**

| Code | Language | Folder |
|---|---|---|
| `en` | English | `values/` (base) |
| `hi` | Hindi | `values-hi/` |
| `es` | Spanish | `values-es/` |
| `fr` | French | `values-fr/` |
| `de` | German | `values-de/` |
| `pt-BR` | Portuguese (Brazil) | `values-pt-rBR/` |
| `pt-PT` | Portuguese (Portugal) | `values-pt-rPT/` |
| `it` | Italian | `values-it/` |
| `ru` | Russian | `values-ru/` |
| `nl` | Dutch | `values-nl/` |
| `tr` | Turkish | `values-tr/` |
| `id` | Indonesian | `values-id/` |
| `pl` | Polish | `values-pl/` |
| `vi` | Vietnamese | `values-vi/` |
| `ko` | Korean | `values-ko/` |
| `ja` | Japanese | `values-ja/` |
| `bn` | Bengali | `values-bn/` |
| `ta` | Tamil | `values-ta/` |
| `te` | Telugu | `values-te/` |
| `mr` | Marathi | `values-mr/` |
| `gu` | Gujarati | `values-gu/` |

- **Invariant rule:** every `values-xx/strings.xml` must include all `settings_language_*` keys (all 22 options) so a user in any locale sees the full language picker — they should never need to revert to English to switch.
- **System integration:** Android 13+ shows the per-app picker in system Settings → Apps → StationAlarm → Language (enabled via `android:localeConfig` and `AppLocalesMetadataHolderService`).
- **Geocoding language hint:** `MapSearchViewModel.currentLang()` reads `Locale.getDefault().language` (post-override) and ships it as `lang=` on every `/search` and `/reverse` call. Worker translates per-provider (LocationIQ uses `Accept-Language` header).
- **Adding a new language:** create `values-xx/strings.xml` (copy base English, translate all strings), add `<locale android:name="xx"/>` to `res/xml/locales_config.xml`, add `"xx" to stringResource(R.string.settings_language_xx)` to `SettingsScreen.kt` `languageOptions`, add `settings_language_xx` key to **every existing** `values-*/strings.xml` (including all 21 above).

---

## Remote Config — emergency knobs

All read via `util/AppRemoteConfig` flows. Refreshed twice per launch: from defaults right after `setDefaultsAsync` (first composition has values), then again after `fetchAndActivate()` (server values applied).

| Key | Type | Default | Effect |
|---|---|---|---|
| `geocoding_backend_url` | string | hardcoded Worker URL | Override Retrofit base URL without an APK update. Already in production use. |
| `min_supported_app_version` | long | 0 | If `BuildConfig.VERSION_CODE` is below this, AppRoot shows a blocking `ForceUpdateDialog`. Debug builds are immune. |
| `maintenance_mode` | boolean | false | Renders a non-dismissable amber `MaintenanceBanner` at the top of the main screen. Rest of the app stays usable. |
| `maintenance_message` | string | "" | Body text for the banner. Falls back to a generic line when empty. |
| `feature_flags` | string JSON | "{}" | `Map<String, Boolean>` for future feature gates. Malformed JSON parses to empty map (never crashes). Read via `AppRemoteConfig.featureFlagsFlow`. |

---

## Database Schema (Room v6)

### `active_stations` table (`ActiveStationEntity`) — the only table
Tracks currently active alarms. Status enum: `MONITORING`, `ALERTING`, `PAUSED`.
`stationId, alertDistanceKm, notify, vibrate, sound, customReminder, sendReminder, status, lat, lon, stationName, lastTriggeredAt`

`saved_places` was dropped in v6 — the PAUSED state on `active_stations` covers the UX that table was added for, and a single source of truth is simpler.

Migration policy: `.fallbackToDestructiveMigrationFrom(1)` — legacy v1 dev DBs wipe instead of crashing. Bump version + write migration for any schema change going forward.

Migrations:
- `MIGRATION_4_5` — adds `lat`, `lon`, `stationName` to `active_stations` (custom map-pin alarms survive reboot).
- `MIGRATION_5_6` — drops `saved_places`, adds nullable `lastTriggeredAt` to `active_stations`.

---

## Graveyard — Rejected Decisions (Do Not Revisit Without Strong Reason)

| Decision | What was rejected | Why |
|---|---|---|
| Mapbox geocoding | Using Mapbox for search | TOS violation — geocoding results cannot be displayed on non-Mapbox maps |
| Rotating API keys | Multiple LocationIQ keys to beat rate limits | TOS risk |
| NaPTAN / EU GTFS / AU feeds | Static station data for Europe/Australia | Scope too large, deferred to Phase 4 |
| Nominatim direct | As primary geocoder | Rate limits too tight for production |
| GADM offline boundaries | Point-in-polygon city tagging | Overkill for current scope |
| Photon by Komoot fallback | Was a second geocoder for when LocationIQ failed | Removed 2026-05; single LocationIQ source + Worker-side cache + queue is enough |
| `layers` filter on the LocationIQ call | Allowlist of OSM layers | Excluded `amenity`/POI results (bus stands, stations, airports) |
| Watchdog as terminal alert | Single 60s stall → loud user-visible notification | Misfired on every transient dip; no self-heal attempted |
| Delete-on-dismiss | Removing the active_stations row when alarm fires | Lost user config; replaced with `PAUSED` state |

---

## Session Log

### Session: 21-Language Translation Pack (2026-06-12)

Full translation coverage shipped across 19 new locale resource folders. Commit `2f47228` on `feature/glass-ui`.

**Files created (19 new `values-xx/strings.xml`):**
- European: `es`, `fr`, `de`, `pt-rBR`, `pt-rPT`, `it`, `ru`, `nl`
- Global: `tr` (Turkish), `id` (Indonesian), `pl` (Polish), `vi` (Vietnamese), `ko` (Korean), `ja` (Japanese), `bn` (Bengali)
- Indian regional: `ta` (Tamil), `te` (Telugu), `mr` (Marathi), `gu` (Gujarati)

**Files modified:**
- `res/xml/locales_config.xml` — added 11 new `<locale>` entries (tr, id, pl, vi, ko, ja, bn, ta, te, mr, gu); total now 21 locales + `en` base.
- `res/values/strings.xml` — added 11 new `settings_language_*` keys (Turkish → Gujarati).
- `res/values-hi/strings.xml` — added same 11 keys to Hindi file.
- All 8 existing European files (es, fr, de, pt-rBR, pt-rPT, it, ru, nl) — back-filled the 11 new language name strings so their language pickers are complete.
- `ui/screens/SettingsScreen.kt` — `languageOptions` expanded from 11 to 22 entries.

**Invariant maintained:** every translation file includes all 22 `settings_language_*` keys so any user switching to any language still sees the full picker without reverting to English.

**Compile check:** `./gradlew compileDebugKotlin` → `BUILD SUCCESSFUL`. Only pre-existing deprecation warning in `AlarmActivity.kt` (unrelated).

---

### Session: Provider-Neutral Geocoding + Full i18n + Remote Config (2026-05-21)

End-to-end refactor in three layers so the app no longer cares which geocoder is behind the Worker, every UI string is localizable, and we have remote kill-switches for production. 4 commits on `feature/glass-ui`.

**Phase 1 — Provider-neutral geocoding contract (commit `dcad785`):**
- App wire format reduced to 5 fields per result: `id`, `name`, `formattedAddress`, `lat`, `lon`. Dropped `confidence` (UI didn't show it), `subtitle` (replaced by `formattedAddress` — full address shown below name), structured `address` block (YAGNI pre-launch), `provider` tag (one provider at a time).
- New `network/GeocodingService.kt` (replaces `LocationIqService.kt`). New params: `q`, `limit`, `bias_lat`, `bias_lon`, `lang`. All LocationIQ-specific knobs (`dedupe`, `normalizecity`, `normalizeaddress`) gone — Worker adds them server-side.
- `bias_lat`/`bias_lon`/`lang` ride in the request even though LocationIQ ignores bias and consumes `lang` as `Accept-Language` — contract is forward-compatible with Photon/Mapbox.
- `GeoSearchResult` data class kept its name (renaming would be churn-only; documented in the file header).
- UI truncation fix: `SearchResultRow` + `BottomControlBar` subtitle line bumped from `maxLines=1` to `maxLines=2` so the full geocoded address is readable.
- 8 new pure-Gson tests in `GeocodingResponseParseTest` cover empty results, blank address, NaN/zero/out-of-range coordinate filtering.

**Phase 2 + 3 — Localization (commit `88cc1d9`):**
- ~180 user-facing strings extracted from 12 Kotlin files into `res/values/strings.xml`. Logger event tags, Crashlytics keys, Analytics event names deliberately stay as inline strings — they're machine identifiers, not user copy.
- `UserPreferences.KEY_APP_LOCALE` + `appLocaleFlow` + `setAppLocale(localeTag)`. Sentinel `"system"` means "follow device locale"; any other value is a BCP 47 tag.
- `StationAlarmApplication.applyPersistedLocale()` runs in `onCreate()` BEFORE any Activity is created. Reads the locale via `runBlocking { ... .first() }` (one-time disk read, ~10ms) and hands it to `AppCompatDelegate.setApplicationLocales(...)`.
- `res/xml/locales_config.xml` lists supported locales (just `en` today). `AndroidManifest.xml` declares `android:localeConfig` + `AppLocalesMetadataHolderService` so the in-app picker shows up in system Settings → Apps → StationAlarm → Language on Android 13+.
- Settings → Language section lists `System default` + `English`. Selecting a non-default option persists + immediately applies via `AppCompatDelegate` — Activities recreate with the new locale.
- Added `testOptions { unitTests { isIncludeAndroidResources = true } }` to `build.gradle.kts` so Robolectric tests can resolve string resources at runtime. Without this every `getString()` in a tested ViewModel throws `Resources$NotFoundException`.
- 4 new tests in `UserPreferencesLocaleTest` cover default-is-system, round-trip writes, sentinel-clears-override, BCP 47 region tag preservation.

**Phase 4 — Remote Config emergency knobs (commit `76b753d`):**
- New `util/AppRemoteConfig.kt` owns 4 RC keys (see Remote Config section above).
- `StationAlarmApplication` registers defaults for all 4 keys alongside the existing `geocoding_backend_url`, and calls `AppRemoteConfig.refresh(rc)` twice — once from defaults right after `setDefaultsAsync` (first composition has values), once again after `fetchAndActivate()` (server values applied).
- `AppRoot` collects the three user-facing flows. If `forceUpdate` is true → renders blocking `ForceUpdateDialog` and early-returns (no map, no alarms, no settings reachable). Else → renders sticky amber `MaintenanceBanner` above `AppNavigation` when `maintenance_mode` is on.
- `feature_flags` parsed via Gson `JsonParser` rather than `org.json.JSONObject` because the latter is a stub on the pure-JVM unit-test classpath that silently returns empty.
- 8 new tests in `AppRemoteConfigTest` cover every parse edge case (empty, blank, single boolean, multiple booleans, non-boolean values dropped silently, malformed JSON, arrays) + the debug-suppresses-force-update contract.

**Aggregate test status:** 39/39 unit tests pass across 6 suites (DAO, UserPreferences, UserPreferencesLocale, GeocodingResponseParse, MapSearchViewModelError, AppRemoteConfig).

**Pending deployment:** the Worker code at `https://stationalarm-geo.mohammedomama2005.workers.dev/` still speaks the OLD wire format. App search/reverse will fail at runtime until the Worker is updated in the Cloudflare dashboard. New `worker.js` was delivered in the chat — paste into the in-browser editor and click Deploy.

### Session: Escalating Alarm, Battery UX, Alarm Pipeline Hardening & Observability (2026-05-17 – 2026-05-21)

13 commits across 5 days shipped between the Bluetooth session and the Provider-Neutral Geocoding session. Covers escalating volume, battery onboarding, three alarm-pipeline reliability fixes from the hardening plan, DB v5→v6, two UX features, and a full observability layer.

**Escalating alarm + low-volume warning (`5a0b9a0`):**
- Alarm now ramps from 5% → 100% over a configurable window (default 30s, range 15–60s) instead of starting at full blast. Default ON.
- `UserPreferences`: `escalating_alarm_enabled` + `escalating_alarm_ramp_secs` keys.
- `AlarmAudioController`: mainHandler-based Runnable ramps `setVolume` each second; ramp teardown in `stopAlarmSound` and `finishTerminalFailure`.
- `LocationService`: caches escalating prefs, passes to `playAlarmSound`.
- `SettingsScreen`: toggle + snapping slider (15–60s, 5s steps) in Audio Routing card.
- `AppRoot`: low-volume warning dialog after alarm confirmation (chains after battery sheet if both conditions apply).

**Battery optimization onboarding (`cbc856f`):**
- New `BatteryOptimizationSheet` bottom sheet + `BatteryOptimizationHelper` utility.
- Prompts users to exempt the app from battery restrictions, ensuring `LocationService` survives on OEM-restricted devices (MIUI, OneUI, Realme).
- Sheet shown after alarm confirmation when `isIgnoringBatteryOptimizations()` returns false.

**Alarm pipeline reliability — 3 fixes from the hardening plan:**
- **Ghost alarm prevention (`ac8b137`):** On process kill mid-alarm, `alertingStationIds` is lost but the DB row stays `ALERTING`. On service restart, `syncWithDatabase` diffed against the empty in-memory set and re-fired every `ALERTING` row without user interaction. Fix: `resetAllAlertingToMonitoring()` is now awaited before the flow subscribes — first emission reflects the cleared state. Also: each `preferences.first()` fallback in `fireAlert()` wrapped in try-catch with sensible default (corrupted DataStore previously cancelled `fireAlert` mid-execution with the wake lock held and the station still `ALERTING`).
- **GeofenceBroadcastReceiver wake lock bridge (`077d570`):** CPU could suspend in the window between `goAsync()` finishing and `LocationService` acquiring its own wake lock. Under Doze/MIUI Deep Sleep this dropped geofence events entirely. Fix: 10-second `PARTIAL_WAKE_LOCK` acquired as the first line of `onReceive()`, released after `startForegroundService` completes. Also: `BackgroundServiceStartNotAllowed` and vendor security exceptions now caught — station reset to `MONITORING` so a later geofence trip can retry cleanly.
- **Battery sheet "Don't ask again" (`7709c5d`):** Sheet was shown on every alarm set/edit if not exempted, training reflexive dismissal. Fix: `battery_opt_permanently_dismissed` boolean pref in `UserPreferences`; third button "Don't ask again" (error tint) on `BatteryOptimizationSheet`; `AppRoot` gates the sheet on `!batteryOptDismissed` in addition to the existing exemption check.

**DB v5 → v6 + UX features:**
- **Schema refactor (`81d6bc9`):** `saved_places` table dropped (vestige from an earlier favouriting plan; `PAUSED` state covers the same UX). `MIGRATION_5_6` drops the table and adds nullable `lastTriggeredAt` to `active_stations`. Removed: `SavedPlace.kt`, `SavedPlaceEntity.kt`, `SavedPlaceDao.kt`, `FavoritesBottomSheet.kt`, and all callers.
- **Editable alarm name + last-triggered timestamp (`617fb7d`):** Bottom sheet pre-fills alarm name from search result/geocoded address ("Dropped Pin" → "Alarm"). Inline rename affordance on each station card (no extra screen). `markAlerting()` stamps `lastTriggeredAt`; card renders it as "Today HH:mm" / "Yesterday HH:mm" / "dd MMM HH:mm" via `java.time`.
- **Configurable alarm duration (`70d8fe3`):** Settings → Alarm Duration: 60-second-step slider (60–900s, default 180s). `UserPreferences`: `alarm_duration_secs` with write-time `coerceIn`. `AlarmAudioController.startTimeout` takes `durationMs` as parameter (was hardcoded). Implemented as a coroutine watchdog inside the foreground `LocationService` (avoids `SCHEDULE_EXACT_ALARM` permission cost and Play Store review).

**Reliability hardening (`4eb756c`):**
- `LocationService` stops itself if `resetAllAlertingToMonitoring` throws on cold start (prevents ghost alarm re-fire from partial state).
- Alarm notification channel: `setBypassDnd(true)` — `CATEGORY_ALARM` alone wasn't suppressing fullscreen intent under DND on locked Pixels.
- Per-station `Mutex` (`ConcurrentHashMap<String, Mutex>`) in `StationRepository` guards `markAlerting`, `rearmStation`, and `updateActiveStationSettings` against interleaved DB writes + geofence operations on rapid toggle/edit.
- New `markAlertingSync` (suspending) used by `GeofenceBroadcastReceiver` to avoid race with `resetToMonitoring` on the `startForegroundService`-failure path.
- Firebase init wrapped in try-catch (malformed `google-services.json` or stale GMS no longer crashes the process).
- osmdroid tile cache cut 50 MB → 5 MB (map tab rarely re-opened; 50 MB persistent storage was wasteful).

**Observability:**
- **Test alarm button (`0d8b084`):** `ACTION_TEST_ALARM` intent handled by `LocationService` — plays configured alarm sound for 3s at current routing setting, then stops. Surfaced as "Test alarm (3s)" in Settings → Alarm Sound. If no real alarms armed, service stops itself after test so the foreground notification doesn't linger. Also: `fireAlert()` stamps six Crashlytics custom keys at alarm start (`alarm_station_id`, `alarm_battery_exempt`, `alarm_gps_accuracy_m`, `alarm_audio_route`, `alarm_escalating`, `alarm_duration_secs`).
- **Robolectric unit tests (`5212259`):** 19 new tests across 3 suites: `ActiveStationDaoTest` (reset scope, PAUSED guard, `updateStationName`, `setLastTriggeredAt`), `UserPreferencesTest` (alarm duration clamp), `MapSearchViewModelErrorTest` (network error → snackbar copy mapping for each branch). Deps added: Robolectric 4.11.1, `androidx.test` core-ktx + junit-ktx, `kotlinx-coroutines-test`.
- **Crashlytics + Analytics (`46fd4cd`):** `Logger` gains `breadcrumb()` (CSV + Crashlytics breadcrumb) and `error()` (CSV + non-fatal exception). New `AnalyticsUtil` wraps `FirebaseAnalytics` with swallow-on-throw. Always-on Crashlytics keys at app start: `manufacturer`, `device_model`, `android_sdk`, `battery_exempt`, `alarms_count`. Analytics user properties: `manufacturer`, `android_sdk`, `alarms_count_band`. ~50 `Logger.log` call sites triaged across the alarm pipeline to `breadcrumb`/`error` as appropriate.
- **Manual test plan (`62882af`):** `MANUAL_TEST_PLAN.md` added — 408 lines, ~110 test cases across 22 sections covering every user-facing change on `feature/glass-ui`. Includes 15-min smoke section and a sign-off table.

---

### Session: Bluetooth/Headphone Audio Routing (2026-05-16)
Implemented safety-first dual-route audio routing to ensure alarms ring through both the device speaker and external audio devices (Bluetooth/Wired) by default.

- **Dual-Route Playback:** `AlarmAudioController` now manages two `MediaPlayer` instances: one for the `BUILTIN_SPEAKER` and one for the best available `EXTERNAL` device.
- **Dynamic Device Monitoring:** Integrated `AudioDeviceCallback` to automatically rebuild routes if a device is connected or disconnected during an active alarm (e.g., Bluetooth buds dying mid-journey).
- **Global Preference:** Added `ring_speaker_with_headphones` (default: true) to `UserPreferences` via DataStore, with a corresponding toggle card in the `SettingsScreen`.
- **Cold-Start Safety:** Updated `LocationService.fireAlert` to be a `suspend` function, using `.first()` to fetch fresh preferences from DataStore even on service startup, preventing race conditions.
- **Fail-Safe Routing:** Explicitly excluded unreliable `TYPE_BLUETOOTH_SCO` from alarm routes. `setPreferredDevice` rejection now releases the player to prevent "ghost" audio routes.
- **Room Migration (v5):** Incremented DB version to 5. Migration 4->5 persists `lat`, `lon`, and `stationName` in `active_stations` to ensure map-dropped pins survive reboots without requiring a network lookup.

### Session: GPS Reliability Overhaul - Phase 1 & 2 (2026-05-13)
Resolved critical field failures (50-minute "Stale IPC Pipe" blackout) and optimized battery usage by implementing a robust layered GPS reliability architecture. `./gradlew compileDebugKotlin` clean.

**Phase 1 (Distance-Based Gearbox & Geofence Hardening):**
- **Distance-Based Priority Switching:** `LocationService.adjustPollingInterval()` switches to `PRIORITY_BALANCED_POWER_ACCURACY` (cell/WiFi) when >100km away to save battery, avoiding GPS antenna activation. It switches back to `PRIORITY_HIGH_ACCURACY` at ≤100km.
- **FixQualityGate (L0):** Created `FixQualityGate.kt` pure-logic module to safely demote inaccurate GPS fixes to "hints" with inflated accuracy, rather than discarding them. The gearbox and UI stay updated with low-quality data while alert buffer math becomes more conservative.
- **Extended Geofencing:** Increased geofence tiers from 6 to 9, adding hardware-level wake-up tripwires at 100km, 150km, and 200km (`ActiveStation.kt`, `GeofenceManager.kt`). Survives Doze and app kills.
- **LocationRequest Builder Hardening:** Added `setWaitForAccurateLocation(false)` and `setMaxUpdateDelayMillis(2x)` for faster first-fixes.
- **Orphan Cleanup:** Added an unconditional watchdog notification sweep in `onCreate()`.
- **UX Polish:** Firing a fresh one-shot `getCurrentLocation(HIGH_ACCURACY)` call in `MainActivity.onResume()` for instant fresh data when the user opens the app while actively tracking.

**Phase 2 (FlpClientGuardian, Cold Start & Emergency Fallback):**
- **Nuclear Reset (`FlpClientGuardian.kt`):** Wraps the `FusedLocationProviderClient`. If silent recovery fails 3 consecutive times or times out after 15s, it performs a nuclear reset (destroys old client, requests a fresh IPC handle from GMS, re-registers callbacks) with a 60s cooldown. Directly fixes the 50-minute IPC stale pipe blackout.
- **Layer Coordinator (`GpsReliabilityCoordinator.kt`):** Manages the reliability subsystems:
  - **Cold-Start Hunter (L3):** Forces aggressive 5s polling for the first 90s of a session to guarantee an initial fix.
  - **Emergency Coarse Fallback (L2):** Uses raw `LocationManager.NETWORK_PROVIDER` (bypasses Google Play Services completely) when the Watchdog detects `FLP` struggling (e.g., 3-minute stall). Immune to GMS IPC stalls. Disarms automatically after 3 good FLP fixes.
  - **GNSS Status Monitor (L5):** Monitors visible vs. used satellites for telemetry.
- **Telemetry (`GpsLogger.kt`):** Extended CSV logs with 9 new columns (`gate_decision`, `priority_used`, `sats_visible`, `sats_in_fix`, `is_mock`, `flp_failures`, `flp_client_age_s`, `coarse_armed`, `cold_start`) for rigorous field testing.

### Session: Kotlin data class HashCode Drift & LocationService Leak (2026-04-19)
Fixed a massive, yet silent, logic flaw in `LocationService.kt` that was causing severe notification stiction, GPS drain, and breaking the "Stop All" feature.

**The Problem:**
- `LocationService` stored active monitoring stations in a `ConcurrentHashMap.newKeySet<ActiveStation>()`.
- In Kotlin, a `data class` automatically computes its `hashCode()` using all properties in its primary constructor. `ActiveStation` includes `currentDistanceKm`.
- As the device moved, the GPS loop iteratively updated `active.currentDistanceKm`. This continuously mutated the base mathematical `hashCode` of the object *while it was actively seated inside the hash map.*
- When the station transitioned from `MONITORING` to `ALERTING` (or was stopped collectively by "Stop All"), the tracker executed `monitoringStations.removeAll { predicate }`. 
- Because the `hashCode` had drifted from its initial insertion value, the underlying `ConcurrentHashMap` could no longer reliably locate the node to destroy it. 
- The station became an **irremovable orphan** within the memory set. 
- Because `monitoringStations.isNotEmpty()` was permanently artificially inflated to `true` by these orphans, the GPS wake-locks stubbornly refused to shut down, and the foreground notification was permanently plastered with misleading fallback data (e.g., "Nearest station...").

**The Solution:**
- Refactored `monitoringStations` from a `ConcurrentHashMap` KeySet of `ActiveStation` objects directly into a `ConcurrentHashMap<String, ActiveStation>`, keyed exclusively by the invariant property `stationId`.
- The architecture now interacts with `monitoringStations.keys` and `monitoringStations.values`.
- When commanding a removal (`monitoringStations.remove(stationId)`), the lookup is performed against an immutable `String` hash.
- Orphans are eradicated flawlessly. `monitoringStations` reliably drains to absolute zero on alarm, restoring instantaneous GPS teardown and immediately cancelling the sticky persistent notifications once the service yields its responsibilities to the standalone full-screen alarm overlay.

### Session: Architecture Flaws & Re-arming Logic (2026-04-18)
Fixed multiple architectural oversights regarding Geofence lifecycle and `LocationService` State, which caused duplicate alerts and immediate re-firing if alarms were re-armed directly at the destination point. `./gradlew compileDebugKotlin` clean.

**Geofence Lifecycle Scope Fixes**:
- **Atomic Teardown**: `StationRepository.markAlerting` now invokes `GeofenceManager.removeGeofencesForStation(appContext, stationId)` synchronously as part of its block. Outer geofences no longer stay active while an alarm is ringing.
- **Orphan & Boot Reigstration Strictness**: `StationRepository.reconcileOrphans()` and `reRegisterAllGeofencesNow()` now strictly filter via `status == "MONITORING"` (was `status != "PAUSED"`). Restored boot events and cold-starts will no longer re-register geofences for `ALERTING` stations.

**Wait for Exit Pattern & INITIAL_TRIGGER_ENTER isolation**:
- **GeofenceManager split**: `addGeofencesForStation` split into two distinct API requests. Layers 1-5 continue with `setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)` to immediately wake standard polling. The `alert` layer runs via a separate intent with `setInitialTrigger(0)` so GMS triggers it solely upon a physical boundary entrance, NOT immediate registration when already inside.
- **LocationService evaluating re-arms**: Introduced `needsInitialEvaluation` and `waitingForExit` maps. When re-adding a station to `MONITORING`, if the first GPS fix returns a distance smaller than `alertDistanceKm`, the tracker isolates the ID into `waitingForExit`. The tracker gracefully keeps GPS active, drops the false-flag trigger, and waits until `effectiveDistance > alertDistanceKm` to confidently restore normal threat detection logic.

### Session: UI Redesign & Proximity Bar Fix (2026-04-18)
Two user-reported UX issues (drawer steals map drags; proximity bar pre-filled at setup; random UUIDs leaking into the UI) fixed alongside a full M3 pass across the two primary screens. `./gradlew compileDebugKotlin` clean.

**Drawer gesture gate** (`ui/AppRoot.kt`, commit `67d926f`):
- Old gate `gesturesEnabled = currentScreen == Screen.Main` meant any horizontal drag anywhere opened the drawer, hijacking map pan gestures on the Map tab.
- New compound gate: `currentScreen == Screen.Main && (pagerState.currentPage != 1 || drawerState.isOpen)`. Map tab only responds to swipe-to-close (drawer already open) or the menu icon; other tabs keep swipe-to-open.

**M3 redesign** — `StationConfigBottomSheet.kt`, `HomeScreen.kt`:
- Replaced hardcoded `Color.Gray`/`DarkGray`/`Color(0xFFFFEBEE)` with M3 color roles (`primary`/`onPrimary`, `primaryContainer`/`onPrimaryContainer`, `errorContainer`/`onErrorContainer`, `secondaryContainer`, `tertiaryContainer`, `surfaceVariant`, `outline`, `onSurfaceVariant`) — gives correct contrast in both light and dark themes.
- Introduced token files: `ui/theme/Spacing.kt` (8dp grid: xs/sm/md/lg/xl/xxl via `MaterialTheme.spacing`), `ui/theme/Shape.kt` (M3 shape scale), `ui/theme/SemanticColors.kt` (proximity far/near/imminent semantic colors mapped from theme).
- `StationConfigBottomSheet`: three toggle columns → horizontal `FilterChip` row (Notify/Vibrate/Sound); 200-char supporting-text counter on the reminder field; "Show reminder when alarm fires" switch disables when reminder blank.
- `HomeScreen`: alerting card uses `errorContainer`/`onErrorContainer`, paused card uses `surfaceVariant`, status badges use `tertiaryContainer`/`errorContainer`/`outline`, search field has leading/trailing icons (`Search`/`Close`), empty state has circular `primaryContainer` hero.
- Animated color transitions via `animateColorAsState` on proximity bar colors as the phone crosses thresholds.
- **Constraint noted:** `material-icons-extended` is NOT in deps — must stick to core `Icons.Default.*` set. `EditLocationAlt`/`DeleteOutline`/`NotificationsActive`/`Vibration`/`VolumeUp`/`MapsHomeWork` are unavailable; use `Edit`/`Delete`/`Notifications`/`Place` instead (and the FilterChips have no leading icon).

**UUID leak fix** — new `ui/screens/StationDisplay.kt`:
- `displayableStationCode(id)` returns null for `custom-<uuid>` ids (map-dropped pins generated at `MapSearchViewModel.kt:270`), otherwise returns the id (railway codes like `NDLS`, `BCT`). Callers in `StationConfigBottomSheet` and elsewhere only render the code chip when non-null.

**Proximity bar anchor fix** — `HomeScreen.StationCard` → new `ProximityRow` composable:
- **Root cause:** old code set `val anchor = startDistanceKm ?: station.alertDistanceKm`, combined with an `else if (distanceKm <= alertDistance) 1f` branch. Result: on the very first frame after setup — before `LaunchedEffect` had time to observe the live distance and set the anchor — the bar would jump to near-100% whenever the phone was already inside the alert radius (or worse, appear partially filled randomly).
- **Fix:** per-station `remember(stationId) { mutableStateOf<Double?>(null) }` anchor; `LaunchedEffect` grows-but-never-shrinks the anchor as fixes come in. Progress returns 0 until BOTH `distanceKm` AND `anchorKm` are non-null. Once both are set, `progress = (anchor - distanceKm) / (anchor - alertDistanceKm)`, clamped to `[0, 1]`. Bar starts empty, fills as the train covers ground toward the alert radius, saturates to full at the ring.

**Files modified:** `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/theme/Type.kt` (wired up new tokens), `ui/screens/HomeScreen.kt`, `ui/screens/StationConfigBottomSheet.kt`.
**Files added:** `ui/screens/StationDisplay.kt`, `ui/theme/Spacing.kt`, `ui/theme/Shape.kt`, `ui/theme/SemanticColors.kt`.

### Session: Production Readiness Pass 2 — Real-Bug Wave (2026-04-16)
Audit-driven fixes after re-verifying `plans/splendid-mixing-pelican.md` against actual code. `./gradlew compileDebugKotlin` clean.

**Real bugs (Wave 1):**
- **#A Boot restore ordering** — `BootRestoreWorker.kt`: service start now gated on `reRegisterAllGeofencesNow()` returning all-OK. Failed attempts return `Result.retry()` without starting the service, so users no longer see a "Monitoring…" notification while geofences are silently broken. After 5 retries the worker posts a user-visible "Restart needed" notification (high-priority, on the existing tracking channel) and returns `Result.failure()` rather than retrying forever in silence.
- **#B Atomic add + startup reconciliation** — `StationRepository.kt`: both DAO inserts in `addActiveStation()` are wrapped in `database.withTransaction { … }`; the failure-rollback path is also transactional and now also deletes the orphan `saved_places` row when `customStation != null`. New `suspend fun reconcileOrphans()` re-registers geofences for any non-`PAUSED` active station whose registration may have been lost across process death; runs once from `StationAlarmApplication.onCreate()` on `Dispatchers.IO`. `addGeofencesForStation` is idempotent (overwrites by request ID), so reconciliation is safe to run unconditionally.
- **#D Geofence removal retry + persistent cleanup** — `GeofenceManager.kt` (LOCKED, approved): `removeGeofencesForStation` is now `suspend`, with 3 in-process attempts at 500ms → 1s → 2s backoff via `suspendCancellableCoroutine`. On terminal failure it enqueues the new `GeofenceCleanupWorker` (10 attempts, exponential backoff from 30s, unique per stationId, `ExistingWorkPolicy.REPLACE`). The worker calls a new internal `attemptRemoveGeofencesForStation()` that does NOT enqueue, breaking the recursion. Net effect: leaked geofences are no longer possible against the GMS 100/app cap. Immediate re-fire is already blocked by the `isArmed()` PAUSED gate from the prior session, so this is purely a long-tail leak fix.
- **#E Audio terminal-failure escalation** — `AlarmAudioController.kt` (LOCKED, approved): `playAlarmSound` accepts an optional `onAudioTerminalFailure: (() -> Unit)?` callback fired only when both the user-picked URI AND the system default URI fail (the only remaining "silent alarm" path). `LocationService.fireAlert` (LOCKED, approved) passes a callback that starts the vibrator (if not already vibrating) and calls the new `notifications.showAudioFailureNotification(stationLabel)` (`ServiceNotifications.kt`, LOCKED, approved — high-priority, ongoing, "Alarm fired silently — audio system error at <name>"). Both alarm-teardown branches in `LocationService` (sync auto-prune in `syncWithDatabase` and `handleDismiss`) now also call `cancelAudioFailureNotification()` so the notice doesn't linger after dismiss.

**Polish (Wave 2):**
- **#F SharedFlow buffer** — `StationRepository.errorEvents`: bumped `extraBufferCapacity` to 16 with `BufferOverflow.DROP_OLDEST` so error bursts (e.g., max-cap → re-add → max-cap) don't suspend the producer.
- **#J First-frame station name** — `ServiceNotifications.showAlert` puts `EXTRA_STATION_NAME` (new `companion object` constant) into the full-screen intent. `AlarmActivity.onCreate` reads it and seeds `stationName` state from it, so the first frame shows the real name instead of briefly flashing the raw `stationId` while the async DB lookup loads.
- **#L Invalid coords** — `GeocodingModels.kt`: `toSearchResult()` mappers parse to `Double.NaN` instead of `0.0` on failure; new `GeoSearchResult.hasValidCoords` checks NaN + range + not-(0,0). `MapSearchViewModel` filters results through `hasValidCoords` so degenerate rows can't sneak into the UI as "Null Island" pins.
- **#M Typed network errors** — `MapSearchViewModel.errorMessageFor(Throwable)` branches on `IOException`, HTTP 429, 500..599, default; surfaced via `_searchError`. The location-FAB path also now handles null fix and `addOnFailureListener` with user-readable copy ("No recent location fix" / "Couldn't read your location").
- **#O SAF URI reachability** — `SettingsScreen.kt`: alarm sound row probes the persisted URI via `contentResolver.openInputStream(uri)?.use { true }` on entry; if it fails, shows a "⚠ Sound unavailable — re-select to fix." line in the error color and switches the button label to "Re-select". Complements the fire-path validation already in `LocationService.resolveValidatedAlarmUri`.
- **#Q DataStore writes** — `UserPreferences.kt`: all three setters wrap their `edit { … }` calls in a `runSafely(op, block)` helper that swallows IOException with a Log.e instead of crashing the caller.
- **#V Station data load** — `StationData.kt`: catch path now logs `STATION_DATA_LOAD_FAILED`; null/empty parse logs `STATION_DATA_EMPTY`. Search fallback unchanged — silent empty list was the visible-only failure mode before.
- **2nd-pass:** custom-reminder `OutlinedTextField` in `StationConfigBottomSheet` capped at 200 chars in `onValueChange` (notification body and alarm card both show the full string, so longer text was a cosmetic + DB-bloat hazard).

**Verified-not-bug (per audit recheck):**
- `processLocationUpdate` NPE — already null-safe via `?: continue`.
- Receiver init race — `StationRepository.initialize()` runs synchronously in `Application.onCreate` before any broadcast can land.
- Background-loc not rechecked — `AppRoot.kt` already rechecks on `ON_RESUME`.
- Notification-rationale re-show — intentional per user.

**LOCKED-file changes this session (all approved by user's "carry out all the necessary bug fixes"):** `GeofenceManager.kt`, `AlarmAudioController.kt`, `ServiceNotifications.kt`, `LocationService.kt`.

**New file:** `geofence/GeofenceCleanupWorker.kt`.

### Session: Self-Healing GPS Watchdog (2026-04-16)
Old watchdog treated any 60s stall as terminal and posted a user-visible high-priority notification, so any transient dip on a long trip misfired. Redesigned as a **self-healing status indicator** with tiered escalation. `./gradlew compileDebugKotlin` clean.

- `ServiceNotifications.showWatchdog(title, body)` (LOCKED — approved): now ongoing, `PRIORITY_LOW`, `setOnlyAlertOnce(true)`. Caller supplies copy per cause. Added `cancelWatchdog()`.
- `LocationService.handleWatchdogTick()` (LOCKED — approved):
  - **Tier 0 (healthy):** stall ≤ `max(60s, 3× currentPollingIntervalMs)` → noop.
  - **Tier 1 (silent recovery):** Tier 0 < stall < Tier 2 → `restartLocationUpdates()` + one-shot `getCurrentLocation(PRIORITY_HIGH_ACCURACY, token)`. No UI.
  - **Tier 2 (notify + keep fighting):** stall ≥ `max(180s, 2× currentPollingIntervalMs)` **or** GPS provider off. Copy depends on cause. Forces polling floor to 10s, re-kicks one-shot recovery each tick. Notification re-posts silently with updated age.
  - **Doze suppression:** `PowerManager.isDeviceIdleMode` (true system doze, not screen-off alone) keeps it in silent mode unless GPS is off.
- **Recovery:** any incoming fix in `onLocationResult` (or the one-shot success listener) clears watchdog state and calls `cancelWatchdog()`. Notification disappears automatically — user never has to tap.
- New log events: `WATCHDOG_SELF_HEAL`, `WATCHDOG_TRIGGERED` (with cause), `WATCHDOG_RECOVERED`, `WATCHDOG_RECOVERY_FAILED`.

**Why:** treat the watchdog as a state indicator, not an event. Alarm is never abandoned — polling forced to 10s during Tier 2, recovery kicks continuously.

### Session: Alarm Reliability Fixes (2026-04-15)
Four user-reported critical issues fixed; `./gradlew compileDebugKotlin` clean. Architectural decisions worth preserving:

1. **Re-fire-after-dismiss gate (`isArmed`)** — `GeofenceBroadcastReceiver` previously used `isActive`, which returned true for any row including `PAUSED`, so geofence events queued in GMS during the async `removeGeofencesForStation` window slipped through and re-triggered the alarm. New `StationRepository.isArmed(stationId): Boolean` (false for missing rows or `PAUSED`) is now the single arming check; `ActiveStationDao.getStatus` added to back it.
2. **GPS gate parity** — `AppRoot.onToggleStation` and `onEditStation` now have the same `isGpsEnabled()` gate that `onStationSelected` and `onStartTrip` already had. All four arming paths consistently raise `showGpsDialog` on failure.
3. **Full teardown on last alarm** — `LocationService.syncWithDatabase` (LOCKED — approved) full-empty branch explicitly: `stopLocationUpdates()` (releases fused client + GPS wake lock) → `stopForeground(STOP_FOREGROUND_REMOVE)` → `notifications.cancelForeground()` → `stopSelf()`. Skips the re-notify path entirely on shutdown. `cancelForeground()` is required because `stopForeground(REMOVE)` alone races with a prior `notifyForeground` and leaves the notification visible. Map/config screens use FusedLocation independently of `LocationService`, so this only affects the no-armed-stations case.
4. **Smooth proximity bar** — `HomeScreen.StationCard` bar now anchored to the **largest observed distance per stationId** (remember + LaunchedEffect that grows but never shrinks), wrapped in `animateFloatAsState(tween 900ms)` so polling intervals don't look like teleport jumps.

### Session: Production Readiness Pass 1 (commit `72a360d`, 2026-04-14)
Implemented all "TO-DO IMMEDIATELY" items from `plans/production-readiness.md`. Architecturally meaningful pieces:

- **Signing**: `app/build.gradle.kts` reads optional `keystore.properties` (gitignored along with `*.keystore`/`*.jks`); missing file → unsigned release so debug builds still work.
- **Log hygiene**: `RetrofitClient.kt` only registers `HttpLoggingInterceptor` when `BuildConfig.DEBUG`.
- **Cached alarm URI (LOCKED)**: `LocationService.observeAlarmSoundUri()` collects `UserPreferences.alarmSoundUriFlow` in `onCreate` into `@Volatile cachedAlarmSoundUri`. `fireAlert` reads the var — no more `runBlocking` on Main dispatcher.
- **URI revalidation (LOCKED)**: `LocationService.resolveValidatedAlarmUri()` probes `contentResolver.openInputStream(uri)` before passing to audio; revoked SAF perms / deleted files fall back to default.
- **Geofence Result-typing (LOCKED)**: `GeofenceManager.addGeofencesForStation` returns `Result<Unit>` with `GeofenceRegistrationException` (`MissingPermission`, `StationNotFound`, `MaxStationsReached`, `RegistrationFailed`). `StationRepository` pre-checks max cap, rolls back on failure, emits user-facing strings via `errorEvents: SharedFlow<String>`. `rearmStation()` reverts to PAUSED if re-registration fails (toggle can't lie). Suspend `reRegisterAllGeofencesNow(): Boolean` exists for `BootRestoreWorker`.
- **Notification-permission rationale**: `AppRoot.kt` tracks `POST_NOTIFICATIONS` separately on Android 13+, deep-links to `Settings.ACTION_APP_NOTIFICATION_SETTINGS`, re-checks on `ON_RESUME`.
- **Boot restore via WorkManager**: `BootReceiver` enqueues `OneTimeWorkRequest` with `ExistingWorkPolicy.KEEP` + `BackoffPolicy.EXPONENTIAL`. Worker resets stale `ALERTING → MONITORING` (ALERTING is treated as a transient state — anything still ALERTING at boot is a leftover from a crashed/killed dismiss path), calls `reRegisterAllGeofencesNow()`, starts `LocationService`. Dep added: `androidx.work:work-runtime-ktx:2.9.1`.
- **Versioning**: `versionCode = gitCommitCount()` (`git rev-list --count HEAD`, falls back to 1); `versionName = "1.0.$appVersionCode"`.

### Session: Legal Pages Authored (2026-04-14)
- New standalone repo at `C:\Users\omama\AndroidStudioProjects\stationalarm-legal\` (not yet on GitHub). `privacy.html`, `terms.html`, `index.html`, `style.css` — light/dark via `prefers-color-scheme`.
- Two placeholder tokens to find-replace before deploy: `<dedicated-email-placeholder>`, `<effective-date-placeholder>`.
- Initial commit `4754390`. `AboutScreen.kt` URL constants point to `stationalarm-legal.pages.dev` (correct target).
- **External next steps** (manual, outside Claude): create public GitHub repo `stationalarm-legal`, Cloudflare Pages → Connect to Git → no framework/build, deploy. Then create dedicated Gmail and find-replace both placeholder tokens.

### Session: Production Setup & Security Audit (2026-04-10)
- Firebase integration: `google-services.json` secured via `.gitignore`.
- Security audit: `git ls-files` + grep — zero API keys, tracking IDs, or keystores tracked.
- Remote Config: `geocoding_backend_url` is the only RC key in use.

### Session: Modularity Refactor (2026-04-07)
`service/LocationService.kt` slimmed 819 → ~430 lines. Extracted same-package: `AlarmAudioController.kt`, `ServiceWakeLocks.kt`, `ServiceNotifications.kt`. UI extractions: `OsmMapView.kt` out of `MapSearchScreen.kt`; `AppRoot.kt` + `AppNavigation` out of `MainActivity.kt`. Current state of these files is the canonical record — see code, not log entries.

---

## Pending

### Must Do Before Play Store / Field Trust
- **Create release keystore:** Generate `.keystore`, write `keystore.properties` (storeFile/storePassword/keyAlias/keyPassword) to project root, confirm `./gradlew assembleRelease` produces a signed AAB.
- **Deploy legal pages:** Push `stationalarm-legal` repo to GitHub, connect Cloudflare Pages, verify `/privacy` + `/terms` load. Find-replace both placeholder tokens.
- **Battery optimization onboarding:** First-launch dialog + deep-link to `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (prevents "missed alarm" reviews on MIUI/OneUI). `battery_sheet_dont_ask_again` pref already in `UserPreferences` — sheet suppresses after first skip.
- **Localization complete** ✓ 21 languages shipped. Next: proof-read Indian regional scripts with a native speaker before publishing.

### On-Device Verification (since last device sweep)
- **Boot restore (Pass 2):** Reboot with armed stations + airplane mode → confirm `BootRestoreWorker` retries without starting the service; re-enable network → restore completes. Reboot 5+ times rapidly while offline → "Restart needed" notification appears.
- **Reconcile orphans:** `adb shell am force-stop` mid-`addActiveStation` → relaunch → `RECONCILED` log fires, alarm fires correctly on test trip.
- **Geofence cleanup worker:** Dismiss alarm under airplane mode → re-enable network → check logcat for `GEOFENCE_CLEANUP_OK` from worker.
- **Audio terminal-failure escalation:** Force the rare double-failure path → confirm vibration starts and "Alarm fired silently" notification appears.
- **Watchdog tiers:** Long ride with brief signal dips → no notification (Tier 1 self-heal). Sustained loss → Tier 2 notification appears, vanishes on first fix.
- **High-velocity polling:** Mock at 150–250 km/h → adaptive tracker fires before passing threshold.
- **Failover chaos drill:** Misconfigure RC URL to force 502 → search surfaces the right error copy (not a silent fail) and the user can still drop a map pin manually.

### Low Priority (noted, not scheduled)
- `StationConfigBottomSheet` param `initialNotes` → rename to `customReminder`.
- "Remove all alarms" feature.
- Europe/Australia station data (NAP/GTFS) — Phase 4.

### Future (Phase 4)
- Play Store submission.
- Europe station data (NAP/GTFS).
