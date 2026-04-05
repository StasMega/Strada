# Strada

Strada is a lightweight real‑time public transport map for Tallinn, built with Kotlin and Jetpack Compose. It combines live GPS positions from `transport.tallinn.ee` to show vehicles smoothly on an interactive map, and static data from Tallinn open GTFS data `https://eu-gtfs.remix.com/tallinn.zip`

## Highlights
- Real‑time vehicle tracking (buses and trams)
- MapLibre
- Local timetable database, so departure times are available offline
- 'Favourite' lines feature to improve performance on low-end devices
- Dynamic route icons
- Monet / Material You dynamic colors (Android 12+) + manual theme modes. (WIP)
- Dark theme (WIP)
- Manual/Auto (Only on WiFi/on any network) departure times local database update


## Data sources
- Live GPS: `https://transport.tallinn.ee/gps.txt` 
- GTFS: ~~`https://transport.tallinn.ee/data/gtfs.zip`~~ `https://eu-gtfs.remix.com/tallinn.zip`


## Status
Strada is a personal project for now, but as it eventually comes out of beta, I'll list it on Play Store. Strada will always be ad-free
