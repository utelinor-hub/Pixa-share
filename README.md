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

## The real Android app (v1.3.0)

- `app/pixa-share.apk` — a signed, full-screen native Android app
  (package `com.shivam.pixashare`, minSdk 24 / targetSdk 34, ~85 KB).
  No browser dependency: it runs the app in a system WebView with its
  own icon + name, receives Gallery → Share → PIXA SHARE natively
  (SEND / SEND_MULTIPLE image/*) and wires the page's photo picker to
  the Android picker. Download link lives inside the app itself.
- Sources + reproducible build live in `android/` — run
  `bash android/build_apk.sh` (aapt2 + ECJ + d8 + zipalign + apksigner,
  no Gradle / Android Studio needed). The debug keystore is kept in the
  repo so every future build updates cleanly over the installed app.

## Google sign-in inside the APK (v1.4.0)

- The Android WebView has **no popup windows**, so the GIS token popup
  (`google.accounts.oauth2`) can never open there — that silently broke
  sign-in in v1.3.0.
- Fix: inside the app (`window.pixaNative` present) the OAuth implicit
  flow runs **as a full page**. The page asks the bridge
  (`pixaNative.openAuth`) to load `accounts.google.com/o/oauth2/v2/auth`
  with `redirect_uri=storagerelay://https/utelinor-hub.github.io?id=...`
  — the very URI the GIS popup itself would use, so Google accepts it
  with **no console changes**. On consent Google redirects there with
  `#access_token=...`; the app intercepts that scheme, parses the token
  and hands it back to the page via `window.__pixaAuthDeliver(...)`.
- Verified against Google: the same request with a bogus redirect_uri
  gets `redirect_uri_mismatch`, ours gets the real sign-in page.
- Browser/PWA sign-in is untouched (still the GIS popup).

## The blank-screen fix (v1.4.1)

- v1.4.0's storagerelay delivery turned out to need a **popup opener**:
  run as a full page, Google's relay page just hangs after consent —
  an invisible page over the WebView's dark background = the blank
  dark-blue screen users saw right after signing in.
- Fix: the in-app OAuth now ends with a **real redirect to our own
  `oauth.html`** (standard OAuth implicit flow). Google 302s there with
  `#access_token=…`; the page (20 lines of static JS, precached) saves
  the response in localStorage and hops back into the app shell, where
  boot code (`consumeOAuthResult`) completes the sign-in.
- **One-time Google Console step** (required): console.cloud.google.com →
  APIs & Services → Credentials → the PIXA OAuth client →
  Authorized redirect URIs → add
  `https://utelinor-hub.github.io/Pixa-share/oauth.html`
- APK hardening: a 3-min watchdog brings the user back from any hung
  Google page (30 s for silent renew), and popup-window redirects route
  the OAuth response through the collector instead of raw loadUrl.

## Sign-in is fully live (v1.4.2)

- The Console step above is **done**: the auth probe now returns the real
  sign-in page (no more `redirect_uri_mismatch`), so in-app sign-in works
  end to end: Sign in → Google page → pick account → `oauth.html` saves
  the token → back in the app, signed in.
- The sign-in now **toasts every step** (page + APK shell): opening, a
  stuck Google page (watchdog recovery) and connection failures all say
  what happened — a silent hang can never look like a blank screen again.
- Verified live: state-mismatched fake tokens are rejected by
  `consumeOAuthResult`, matching ones complete the sign-in.

© SHIVAM PIXA project.
