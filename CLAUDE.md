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
- Core Engine: LocationService + GeofenceManager + GeofenceBroadcastReceiver (unchanged)
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

## Current Files / Modules to Know
- StationRepository.kt (central data access)
- MapSearchScreen.kt + MapSearchViewModel.kt (new map module)
- StationConfigBottomSheet.kt (shared by both modules)
- LocationService.kt + GeofenceManager.kt (core engine - do not touch)
- Room DB with SavedPlaceEntity

## Non-Functional Goals
- Battery efficient
- Reliable at high speeds
- No visible API keys in APK
- Offline-first where possible
- Scalable to global use (India + Europe)

You are now fully briefed on the entire project. When I ask for code or changes, always respect the existing architecture, especially do not touch the core alarm engine (LocationService / GeofenceManager).

Ask me questions if anything is unclear.