const CACHE_NAME = `obd-v2.0.83`;

// Never cachs
const nc = [/microsoft-identity-association/];

// StaleWhileRevalidate
const swr = [];

// Precache
let pcb = ["script.js", "style.css"];
// do NOT precache index.html or service-worker.js so a plain page refresh will fetch
// the latest HTML from the network. We'll keep root in the cache list if desired,
// but prefer not to precache index.html to avoid stale landing pages.
pcb.push("/"); // add root to cache

// Passed into catch block of a fetch so could access err
// event if needed. We#re assuming that any error from a
// fetch indicates offline.
var notifyOffline = function () {
  self.clients.matchAll().then((clients) => {
    clients.forEach((client) => client.postMessage({ offline: true, online: false }));
  });
};

// Passed into then block of a fetch so pass through the
// result
var notifyOnline = function (response) {
  self.clients.matchAll().then((clients) => {
    clients.forEach((client) => client.postMessage({ online: true, offline: false }));
  });
  return response;
};

self.addEventListener("install", function (event) {
  console.log(`Install event called for ${CACHE_NAME}`);
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      cache.addAll(pcb);
    })
  );
});

self.addEventListener("activate", function (event) {
  console.log(`Activate event called for ${CACHE_NAME}`);
  event.waitUntil(
    caches.keys().then(function (cacheNames) {
      return Promise.all(
        cacheNames.map(function (cacheName) {
          if (CACHE_NAME !== cacheName) {
            console.log(`Removing old cache: ${cacheName}`);
            return caches.delete(cacheName);
          }
        })
      );
    })
  );
});

self.addEventListener("fetch", function (event) {
  // Parse the URL:
  const requestURL = new URL(event.request.url);

  // Navigation requests: try network first so users get the latest HTML when
  // online, but fall back to the cached root page when offline so the PWA still works.
  if (requestURL.pathname === "/" || requestURL.pathname.endsWith("/index.html")) {
    NetworkFirst(event);
    return;
  }

  // Never cache the service worker file itself - always go to network so a
  // page refresh can fetch the latest worker.
  if (requestURL.pathname.endsWith("/service-worker.js")) {
    NetworkOnly(event);
    return;
  }

  const isSWR = swr.reduce(function (hadMatch, nextRegex) {
    return hadMatch || nextRegex.test(requestURL);
  }, false);

  if (isSWR) {
    StaleWhileRevalidate(event);
    return;
  }

  const isNC = nc.reduce(function (hadMatch, nextRegex) {
    return hadMatch || nextRegex.test(requestURL);
  }, false);

  if (isNC) {
    NetworkOnly(event);
    return;
  }

  // same origin
  if (new RegExp(`^${self.origin}`).test(requestURL)) CacheWithNetworkFallback(event);
});

/**
 * If it's in the cache use it. But always then go to the network for a fresher
 * version and put that in the cache.
 */
var StaleWhileRevalidate = function (event) {
  const id = (Math.random() * 1000000).toString().substr(0, 4);
  console.log(`${id}|| SWR for: ${event.request.url}`);
  event.respondWith(
    caches.open(CACHE_NAME).then(function (cache) {
      return cache.match(event.request).then(function (response) {
        console.log(`${id}|| SWR in cache: `, !!response);
        const fetchPromise = fetch(event.request)
          .then(function (networkResponse) {
            console.log(`${id}|| SWR caching the network response`);
            cache.put(event.request, networkResponse.clone());
            notifyOnline();
            return networkResponse;
          })
          .catch(notifyOffline);
        console.log(`${id}|| SWR returning response or fetchPromise`);
        return response || fetchPromise;
      });
    })
  );
};

var CacheWithNetworkFallback = function (event) {
  const id = (Math.random() * 1000000).toString().substr(0, 4);
  console.log(`${id}|| CWNF for: ${event.request.url}`);
  event.respondWith(
    caches.match(event.request).then(function (response) {
      console.log(`${id}|| CWNF (${event.request.url}) in cache: ${!!response}`);
      return response || fetch(event.request).then(notifyOnline).catch(notifyOffline);
    })
  );
};

var NetworkOnly = function (event) {
  console.log(`${event.request.url} || network only`);
  event.respondWith(fetch(event.request).then(notifyOnline).catch(notifyOffline));
};

// Network-first strategy for navigations: try network, cache and return. If
// network fails, try to return cached root (`/`) so the app still loads offline.
var NetworkFirst = function (event) {
  const id = (Math.random() * 1000000).toString().substr(0, 4);
  console.log(`${id}|| NetworkFirst for: ${event.request.url}`);
  event.respondWith(
    fetch(event.request)
      .then(function (networkResponse) {
        // update cache with latest HTML for offline fallback
        try {
          if (networkResponse && networkResponse.ok) {
            const copy = networkResponse.clone();
            caches.open(CACHE_NAME).then(function (cache) {
              cache.put(event.request, copy);
            });
          }
        } catch (e) {
          /* ignore cache put errors */
        }
        notifyOnline(networkResponse);
        return networkResponse;
      })
      .catch(function () {
        console.log(`${id}|| Network failed, trying cache fallback`);
        return caches.match(event.request).then(function (response) {
          if (response) return response;
          // fallback to root page cached at install
          return caches.match("/").then(function (rootResp) {
            if (rootResp) return rootResp;
            // last resort: throw to trigger offline notifier
            throw new Error("No cached navigation response");
          });
        });
      })
      .catch(function (err) {
        notifyOffline();
        return new Response("Offline", { status: 503, statusText: "Offline" });
      })
  );
};
