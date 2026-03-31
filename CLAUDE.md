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
- Geocoding: Mapbox forward & reverse geocoding (currently)
- Local Database: Room SQLite (version 3)
- Core Engine: LocationService + GeofenceManager + GeofenceBroadcastReceiver (unchanged)
- Static Data: StationData.kt (hardcoded Indian railway stations)
- Custom Places: SavedPlaceEntity in Room (saved from map tap or search)

## Key Architecture Decisions
- Two search modules that converge into the same Station domain model:
  1. Static India railway list (My Stations tab)
  2. Map module (MapSearchScreen) with forward/reverse geocoding + live radius circle
- The map module must integrate seamlessly with the existing StationConfigBottomSheet and core alarm engine without modifying the service layer.
- Future requirement: Backend proxy (Supabase or Node.js) to hide API keys and allow switching providers without app updates.

## Important Constraints & Decisions
- We want to avoid vendor lock-in (especially Mapbox's "must use our map" rule).
- We are considering LocationIQ + OSM as primary (with fallback strategy for the 5k/day limit).
- Long-term plan: Backend proxy → possible migration to self-hosted Nominatim.
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