# PIXA SHARE

**The mobile companion of SHIVAM PIXA** — a clean, InShare-style app for the
phone. Photos clicked on the phone travel to **SHIVAM PIXA on the PC at
ORIGINAL quality** — same size, same resolution, same ratio, nothing is
re-encoded. Works over home WiFi **and** mobile data.

**Live app:** https://utelinor-hub.github.io/Pixa-share/

This app is fully standalone. It shares **no code** with the
[SHIVAM PIXA](https://github.com/utelinor-hub/shivam-pixa) PC app — the two
projects only agree on a small **Drive protocol** (below), the same way any
sender and receiver agree on a file format.

## How it works

1. Sign in with the **same Google account** that PIXA on the PC uses.
   Photos flow through **your own Google Drive** — the `SHIVAM PIXA` folder
   in it. Nothing passes through any third-party server.
2. Pick photos (or use **Gallery → Share → PIXA SHARE** on Android) — every
   file is queued in the app and uploaded **byte-for-byte as-is**.
3. The big **Auto-send to PC** switch keeps the app sending on its own:
   Wake Lock keeps the app alive while sending, and a Background Sync
   flushes the queue even after the app is closed. On the PC, SHIVAM PIXA
   (v3.30.0+) adopts the arriving photos automatically into a
   **PIXA SHARE** group.

## Drive protocol (the only contract with SHIVAM PIXA)

| Item | Value |
| --- | --- |
| Folder | `SHIVAM PIXA` (root of the user's Drive) |
| Photo marker | `appProperties: { pixa: "share" }` on every uploaded file |
| Preview | a small JPEG uploaded next to each photo as `<name>.thumb.jpg` |
| Heartbeat | `pixa-share-mobile.json` in the folder, rewritten while signed in (lets the PC warn if the phone uses a different Google account) |

## Tech

- Pure HTML/CSS/JS — no build step, no frameworks, no tracking.
- Installable PWA: own Service Worker (offline shell + Android share
  target + background uploader + v1.1.0 auto-update pipeline), own
  manifest and icons.
- Runs entirely on GitHub Pages.

## Brand (v1.2.0)

- App icon = the same Picasa colour-wheel artwork as PIXA on the PC
  (icon-192-v2 / icon-512-v2 / icon-maskable-512-v2 — the `-v2`
  filenames cache-bust the manifest so Android refreshes the launcher
  icon + app name on the next app open).
- Header = the animated PIXA logo: `logo-ring-v2.png` (colour wheel)
  spins slowly while `logo-core-v2.png` (white disc + shutter emblem)
  stays still — the RR-badge effect — over an Aboreto "PIXA SHARE"
  wordmark with a shine sweep. The font is embedded as woff2, so it
  works fully offline.

© SHIVAM PIXA project.
