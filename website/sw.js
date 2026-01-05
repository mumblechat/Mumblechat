const CACHE_NAME = 'mumblechat-v4';
const urlsToCache = [
  '/',
  '/index.html',
  '/protocol.html',
  '/token.html',
  '/relay-nodes.html',
  '/download.html',
  '/docs.html',
  '/chat.html',
  '/css/styles.css',
  '/js/app.js'
];

// URL rewrite map for clean URLs
const urlRewriteMap = {
  '/protocol': '/protocol.html',
  '/token': '/token.html',
  '/relay-nodes': '/relay-nodes.html',
  '/download': '/download.html',
  '/docs': '/docs.html',
  '/chat': '/chat.html'
};

// Install event
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => {
        console.log('Opened cache');
        return cache.addAll(urlsToCache);
      })
      .catch(err => {
        console.log('Cache install failed:', err);
      })
  );
  self.skipWaiting();
});

// Activate event - clear old caches
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(cacheNames => {
      return Promise.all(
        cacheNames.filter(cacheName => cacheName !== CACHE_NAME)
          .map(cacheName => caches.delete(cacheName))
      );
    })
  );
  self.clients.claim();
});

// Fetch event - Network first, fallback to cache, handle URL rewrites
self.addEventListener('fetch', event => {
  let requestUrl = new URL(event.request.url);
  
  // Handle clean URL rewrites
  if (urlRewriteMap[requestUrl.pathname]) {
    requestUrl.pathname = urlRewriteMap[requestUrl.pathname];
    event.respondWith(
      fetch(requestUrl.toString())
        .then(response => response)
        .catch(() => caches.match(requestUrl.pathname))
    );
    return;
  }

  event.respondWith(
    fetch(event.request)
      .then(response => {
        // Cache successful responses
        if (response && response.status === 200 && response.type === 'basic') {
          const responseToCache = response.clone();
          caches.open(CACHE_NAME)
            .then(cache => {
              cache.put(event.request, responseToCache);
            });
        }
        return response;
      })
      .catch(() => {
        // Network failed, try cache
        return caches.match(event.request);
      })
  );
});

// Activate event - clean up old caches
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(cacheNames => {
      return Promise.all(
        cacheNames.map(cacheName => {
          if (cacheName !== CACHE_NAME) {
            return caches.delete(cacheName);
          }
        })
      );
    })
  );
  self.clients.claim();
});
