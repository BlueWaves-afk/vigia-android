# VIGIA (Android) — Real-time Road Hazard Intelligence

VIGIA is an Android prototype for low-latency hazard awareness + navigation assistance.
It combines on-device perception + local routing logic with optional cloud intelligence.

## Why it matters
- Safer routing and hazard visibility in real-world road conditions
- Works with offline hazard cache + lightweight mode for low-end devices
- Built with a modular MVVM structure for maintainability

## Key Features
- Live map + user location tracking (OSMDroid + Azure Maps tiles)
- Search + route preview (fastest vs safest)
- Hazard state UI + hazard markers
- Offline area download + plotting cached hazards
- “Lite Mode” vs “AI Mode” execution path

## Architecture (MVVM)
- `feature/splash` → device capability detection → passes `IS_LITE_MODE`
- `feature/landing` → app entry → routes to main
- `feature/main` → MVVM (ViewModel + UI state/events) + controllers (map, animator)

## Setup (Secrets)
This repo does NOT include API keys.
Create `local.properties` in the project root and add:

AZURE_BASE_URL=...
AZURE_MAPS_KEY=...
AZURE_EH_STRING=...

## Run
1. Open in Android Studio
2. Sync Gradle
3. Run `app` on device/emulator

## Demo
(Add a short YouTube/Drive link here later)

## Screenshots
(Add later)

## License
Apache-2.0