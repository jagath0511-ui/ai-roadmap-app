const CACHE_NAME = "jai-jagath-ai-v8";
const STATIC_ASSETS = [
  "./",
  "./index.html",
  "./manifest.json",
  "./sm2.js",
  "./curriculum.js"
];

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) return caches.delete(key);
        })
      )
    )
  );
  self.clients.claim();
});

// Cache-First for WASM binaries & Pyodide CDN, Stale-While-Revalidate for app files
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);

  if (url.origin.includes("jsdelivr.net") || url.pathname.endsWith(".wasm") || url.pathname.endsWith(".zip")) {
    e.respondWith(
      caches.open(CACHE_NAME).then(async (cache) => {
        const cachedResponse = await cache.match(e.request);
        if (cachedResponse) return cachedResponse;
        try {
          const networkResponse = await fetch(e.request);
          cache.put(e.request, networkResponse.clone());
          return networkResponse;
        } catch (err) {
          return cachedResponse;
        }
      })
    );
    return;
  }

  e.respondWith(
    caches.match(e.request).then((cached) => {
      const networked = fetch(e.request)
        .then((res) => {
          caches.open(CACHE_NAME).then((cache) => cache.put(e.request, res.clone()));
          return res;
        })
        .catch(() => cached);
      return cached || networked;
    })
  );
});

