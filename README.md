# Strada

Strada is a lightweight real‑time public transport map for Tallinn, built with Kotlin and Jetpack Compose. It combines live GPS positions with GTFS static data from `transport.tallinn.ee` to show vehicles smoothly on an interactive map.

## Highlights
- Real‑time vehicle tracking (buses and trams)
- MapLibre + OpenFreeMap vector styles (light/dark) bundled in `assets` and switched live without losing layers.
- Dynamic route icons
- Monet / Material You dynamic colors (Android 12+) + manual theme modes. 
- English and Russian language available (Estonian coming soon)
- GTFS ZIP parsing on the fly (routes/stops) via `ZipInputStream` (no unzip-to-disk step).


## Data sources
- Live GPS: `https://transport.tallinn.ee/gps.txt` 
- GTFS: `https://transport.tallinn.ee/data/gtfs.zip`


## Status
Strada is a personal project for now, but as it eventually comes out of beta, I'll list it on Play Store. Strada will always be ad-free
