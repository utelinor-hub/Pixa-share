/* PIXA SHARE — Service Worker (v1.4.0)
   Standalone app — lives in its own repo (utelinor-hub/Pixa-share),
   deployed at https://utelinor-hub.github.io/Pixa-share/ .
   ------------------------------------------------------------------
   Jobs:
   1. App shell cache — PIXA SHARE opens instantly and works offline.
   2. Android share target — Gallery → Share → PIXA SHARE: the POST
      lands here, every photo is stashed in a cache queue and the app
      window is woken to ingest it (original bytes are kept untouched).
   3. Background sending — when the page queues photos and closes, a
      'pixashare-autosync' background sync wakes this worker; it reads
      the SAME IndexedDB queue the page uses ('pixashare' → items) and
      uploads pending photos with the saved Google token. This is the
      "application back me run karta rahe" part. The worker cannot
      renew an expired Google token, so if the session has expired it
      simply leaves the queue for the next app open.
   4. v1.1.0 — Android-style auto-update: a new version INSTALLS in the
      background and then WAITS (no immediate skipWaiting). The page
      shows the "Install Update" popup and applies it (user tap or ~9 s
      auto, never mid-send). Safety net: activate anyway after 25 s so
      an update can never stall forever.
   5. v1.2.0 — PIXA brand identity: the icon set moves to cache-busted
      -v2 filenames (same Picasa wheel artwork as PIXA on the PC) so
      Android refreshes the launcher icon + app name, and the shell now
      carries the animated header logo pieces (logo-ring-v2 spins,
      logo-core-v2 stays still).
   6. v1.3.0 — the REAL Android app: app/pixa-share.apk (full-screen
      native shell, own icon, Gallery Share target, photo picker — no
      browser dependency) is precached so the download link works
      offline too.
   7. v1.4.0 — Google sign-in fixed inside the APK: the WebView has no
      popups, so in-app sign-in goes full-page (bridge → accounts.google.com
      → storagerelay:// redirect → back). Shell changes: none, just the
      version. */

const VERSION = 'v1.4.0';
const CACHE = `pixashare-${VERSION}`;
const SHARE_CACHE = 'pixashare-shared-v1';

const DRIVE = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
const FOLDER_NAME = 'SHIVAM PIXA';
const IMG_EXT = /\.(jpe?g|png|gif|webp|bmp|avif)$/i;

const ASSETS = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icons/favicon-v2.png',
  './icons/icon-192-v2.png',
  './icons/icon-512-v2.png',
  './icons/icon-maskable-512-v2.png',
  './icons/logo-ring-v2.png',
  './icons/logo-core-v2.png',
  './app/pixa-share.apk'
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)));
  /* NO skipWaiting here — the page's "Install Update" popup decides when
     to restart (user tap, or ~9 s auto once no photo is sending). Safety
     net: if the popup was missed entirely (stale page in background),
     activate anyway after 25 s so an update can never stall forever. */
  setTimeout(() => self.skipWaiting(), 25000);
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE && k !== SHARE_CACHE && !k.startsWith('shivam-pixa-')).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('message', (e) => {
  if (e.data === 'SKIP_WAITING') self.skipWaiting();
  if (e.data && e.data.type === 'PIXASHARE_VERSION') {
    const port = e.ports && e.ports[0];
    const payload = { type: 'VERSION', version: VERSION };
    if (port) { try { port.postMessage(payload); } catch (err) {} }
    else if (e.source) { try { e.source.postMessage(payload); } catch (err) {} }
  }
  if (e.data && e.data.type === 'PIXASHARE_ENQUEUED') {
    /* new photos queued — register background sync so they leave the
       phone even if the user closes the app right away */
    e.waitUntil(registerSync());
  }
});

async function registerSync() {
  try { if ('sync' in self.registration) await self.registration.sync.register('pixashare-autosync'); } catch (err) {}
}

/* ================= share target (Gallery → Share → PIXA SHARE) ================= */
async function handleSharePost(request) {
  const stashed = [];
  try {
    const form = await request.formData();
    const files = [
      ...form.getAll('photos'), ...form.getAll('files'), ...form.getAll('file'), ...form.getAll('image')
    ].filter((v) => v instanceof File && v.size > 0 && IMG_EXT.test(v.name || ''));
    if (files.length) {
      const cache = await caches.open(SHARE_CACHE);
      const stamp = Date.now();
      for (let i = 0; i < files.length; i++) {
        const f = files[i];
        const safeName = f.name && /\.[a-z0-9]{1,5}$/i.test(f.name) ? f.name : `${f.name || 'photo'}.jpg`;
        const key = `${location.origin}/__shared__/${stamp}-${i}-${safeName.replace(/[^\w.\- ]+/g, '_')}`;
        await cache.put(key, new Response(f, { headers: { 'Content-Type': f.type || 'application/octet-stream' } }));
        stashed.push(key);
      }
      const prev = await cache.match(`${location.origin}/__shared__/__queue__`);
      let queue = [];
      try { queue = (await prev.json()).keys || []; } catch (err) {}
      await cache.put(
        `${location.origin}/__shared__/__queue__`,
        new Response(JSON.stringify({ keys: queue.concat(stashed), at: new Date().toISOString() }), { headers: { 'Content-Type': 'application/json' } })
      );
      await registerSync();
    }
  } catch (err) { /* malformed share — still open the app */ }

  const cs = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  if (cs.length) {
    cs.forEach((c) => c.postMessage({ type: 'SHARED_FILES', keys: stashed }));
    const vis = cs.find((c) => c.visibilityState === 'visible') || cs[0];
    try { await vis.focus(); } catch (err) {}
  } else {
    await self.clients.openWindow('./?shared=1');
  }
  return Response.redirect(new URL('?shared=1', request.url).href, 303);
}

/* ================= background uploader =================
   Runs with the app CLOSED. Uses the same 'pixashare' IndexedDB the
   page writes: kv.sess (Google token) + kv.ids (folder id) + items. */
function idbOpen() {
  return new Promise((res, rej) => {
    const rq = indexedDB.open('pixashare', 1);
    rq.onupgradeneeded = () => {
      const d = rq.result;
      if (!d.objectStoreNames.contains('kv')) d.createObjectStore('kv');
      if (!d.objectStoreNames.contains('items')) d.createObjectStore('items');
    };
    rq.onsuccess = () => res(rq.result);
    rq.onerror = () => rej(rq.error);
  });
}
async function idbGet(store, key) {
  const d = await idbOpen();
  return new Promise((res) => {
    const t = d.transaction(store, 'readonly');
    const rq = t.objectStore(store).get(key);
    t.oncomplete = () => res(rq.result || null);
    t.onerror = () => res(null);
  });
}
async function idbPut(store, key, val) {
  const d = await idbOpen();
  return new Promise((res) => {
    const t = d.transaction(store, 'readwrite');
    t.objectStore(store).put(val, key);
    t.oncomplete = () => res(true);
    t.onerror = () => res(false);
  });
}

function swUpload(blob, meta, token) {
  return new Promise((resolve, reject) => {
    const boundary = 'pixabnd';
    const pre = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(meta)}\r\n--${boundary}\r\nContent-Type: ${blob.type || 'application/octet-stream'}\r\n\r\n`;
    const body = new Blob([pre, blob, `\r\n--${boundary}--`], { type: `multipart/related; boundary=${boundary}` });
    fetch(`${UPLOAD}/files?uploadType=multipart&fields=id`, {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'multipart/related; boundary=pixabnd' },
      body
    }).then(async (r) => {
      if (r.ok) { try { resolve(await r.json()); } catch (e) { resolve({}); } }
      else reject(new Error('HTTP ' + r.status));
    }).catch(() => reject(new Error('Network error')));
  });
}

async function backgroundSend() {
  const sess = await idbGet('kv', 'sess');
  if (!sess || !sess.token || !(sess.exp && Date.now() < sess.exp)) return; /* expired — page renews next open */
  const ids = await idbGet('kv', 'ids');
  let folderId = ids && ids.folderId;
  if (!folderId) {
    const q = encodeURIComponent(`name = '${FOLDER_NAME}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false and 'root' in parents`);
    const r = await fetch(`${DRIVE}/files?q=${q}&fields=files(id)&pageSize=5`, { headers: { Authorization: 'Bearer ' + sess.token } });
    if (!r.ok) return;
    const j = await r.json();
    if (j.files && j.files.length) folderId = j.files[0].id;
    else {
      const cr = await fetch(`${DRIVE}/files`, {
        method: 'POST', headers: { Authorization: 'Bearer ' + sess.token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: FOLDER_NAME, mimeType: 'application/vnd.google-apps.folder' })
      });
      if (!cr.ok) return;
      folderId = (await cr.json()).id;
    }
    await idbPut('kv', 'ids', Object.assign({}, ids || {}, { folderId }));
  }
  const d = await idbOpen();
  const all = await new Promise((res) => {
    const t = d.transaction('items', 'readonly');
    const rq = t.objectStore('items').getAll();
    t.oncomplete = () => res(rq.result || []);
    t.onerror = () => res([]);
  });
  let sent = 0;
  for (const it of all) {
    if (!it || (it.status !== 'waiting' && it.status !== 'failed') || !it.blob) continue;
    try {
      const up = await swUpload(it.blob, { name: it.name, parents: [folderId], appProperties: { pixa: 'share' } }, sess.token);
      it.status = 'sent'; it.progress = 100; it.sentAt = Date.now(); it.fileId = up.id || null;
      delete it.blob;
      sent++;
    } catch (e) {
      it.status = 'failed'; it.err = String(e.message || e); it.nextTry = Date.now() + 60000;
    }
    await idbPut('items', it.id, it);
  }
  if (sent) {
    const cs = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    cs.forEach((c) => c.postMessage({ type: 'PIXASHARE_SYNC' }));
    await registerSync(); /* more may be pending / failures retry */
  }
}

self.addEventListener('sync', (e) => {
  if (e.tag === 'pixashare-autosync') e.waitUntil(backgroundSend());
});

/* ================= fetch ================= */
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return;

  if (
    e.request.method === 'POST' &&
    e.request.mode === 'navigate' &&
    (e.request.headers.get('content-type') || '').includes('multipart/form-data')
  ) {
    e.respondWith(handleSharePost(e.request));
    return;
  }

  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put('./index.html', copy));
          return res;
        })
        .catch(() => caches.match('./index.html'))
    );
    return;
  }

  if (e.request.method === 'GET') {
    e.respondWith(
      caches.match(e.request).then(
        (hit) =>
          hit ||
          fetch(e.request).then((res) => {
            if (res.ok) {
              const copy = res.clone();
              caches.open(CACHE).then((c) => c.put(e.request, copy));
            }
            return res;
          })
      )
    );
  }
});
