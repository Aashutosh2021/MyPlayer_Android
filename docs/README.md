# MyPlayer Website (Android & Windows Edition)

Official responsive static landing page for MyPlayer on Android and Windows Desktop.

## Features Covered
- Direct YouTube link streaming & search
- 9-Second progressive rolling chunk audio engine
- Studio Master DSP & Audio Lab (10-Band Graphic EQ, Sub-Bass Boost, Stereo Widener, Analog Air Exciter)
- Apple-grade AutoMix crossfade
- 6 Now Playing visual styles (Radial, Vinyl, Poster, Waveform, Lyrics Stage, Classic)
- DualBud Mode (Left/Right earbud audio split with instant swap) on Android & Windows
- Compose Multiplatform Windows Desktop Edition with 48kHz hardware PCM & VLCJ decoding
- Multi-device Wi-Fi Sync Play
- Real-time synced lyrics (LRCLIB & offline `.lrc` companion files)
- 1000×1000 Studio HD artwork
- Buy Me a Coffee / UPI micro-donations (`gaashutoshgupta52-22@okaxis`)

## Automatic Latest Package Discovery
`script.js` calls the public GitHub Releases API for:
- Android: `https://api.github.com/repos/Aashutosh2021/MyPlayer_Android/releases/latest`
- Windows: `https://api.github.com/repos/Aashutosh2021/MyPlayer_Windows/releases/latest`

## Hosting & Deployment
Configured for Firebase Hosting (`firebase.json` points to `docs/`) and compatible with GitHub Pages or Render Static Site.
