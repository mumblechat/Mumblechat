const CACHE_NAME = 'mumblechat-v5';
const urlsToCache = [
  '/conversations.html',
  '/css/chat-styles.css',
  '/js/chat/app.js',
  '/js/chat/config.js',
  '/js/chat/state.js',
  '/js/chat/wallet.js',
  '/js/chat/walletDetection.js',
  '/js/chat/relay.js',
  '/js/chat/contacts.js',
  '/js/chat/messages.js',
  '/js/chat/crypto.js',
  '/js/chat/ui.js',
  '/icons/icon-192.svg',
  '/icons/icon-512.svg'
];

// Install - cache core assets
self.addEventListener('install', event => {
  console.log('[SW] Installing MumbleChat service worker...');
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => {
        console.log('[SW] Caching app shell');
        return cache.addAll(urlsToCache).catch(err => {
          console.warn('[SW] Some resources failed to cache:', err);
        });
      })
  );
  self.skipWaiting();
});

// Activate - clean old caches
self.addEventListener('activate', event => {
  console.log('[SW] Activating...');
  event.waitUntil(
    caches.keys().then(cacheNames => {
      return Promise.all(
        cacheNames
          .filter(name => name.startsWith('mumblechat-') && name !== CACHE_NAME)
          .map(name => {
            console.log('[SW] Deleting old cache:', name);
            return caches.delete(name);
          })
      );
    })
  );
  self.clients.claim();
});

// Fetch - network first, fall back to cache
self.addEventListener('fetch', event => {
  // Skip non-GET requests
  if (event.request.method !== 'GET') return;
  
  // Skip WebSocket requests
  if (event.request.url.includes('ws://') || event.request.url.includes('wss://')) return;
  
  // Skip API/relay requests
  if (event.request.url.includes('/api/') || event.request.url.includes(':19371') || event.request.url.includes(':8444')) return;

  event.respondWith(
    fetch(event.request)
      .then(response => {
        // Cache successful responses
        if (response && response.status === 200) {
          const responseClone = response.clone();
          caches.open(CACHE_NAME).then(cache => {
            cache.put(event.request, responseClone);
          });
        }
        return response;
      })
      .catch(() => {
        // Network failed - try cache
        return caches.match(event.request).then(response => {
          if (response) {
            return response;
          }
          // Return offline page for navigation requests
          if (event.request.mode === 'navigate') {
            return caches.match('/conversations.html');
          }
          return new Response('Offline', { status: 503 });
        });
      })
  );
});

// Handle push notifications (future)
self.addEventListener('push', event => {
  const data = event.data?.json() || { title: 'MumbleChat', body: 'New message' };
  
  event.waitUntil(
    self.registration.showNotification(data.title, {
      body: data.body,
      icon: '/icons/icon-192.svg',
      badge: '/icons/icon-72.svg',
      tag: 'mumblechat-notification',
      renotify: true
    })
  );
});

// Handle notification click
self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil(
    clients.openWindow('/conversations.html')
  );
});
