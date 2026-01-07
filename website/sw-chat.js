/**
 * MumbleChat PWA Service Worker
 * 
 * Handles offline caching and push notifications
 */

const CACHE_NAME = 'mumblechat-v2';
const STATIC_ASSETS = [
    '/conversations.html',
    '/css/chat-styles.css',
    '/js/chat/app.js',
    '/js/chat/config.js',
    '/js/chat/state.js',
    '/js/chat/wallet.js',
    '/js/chat/relay.js',
    '/js/chat/contacts.js',
    '/js/chat/messages.js',
    '/js/chat/groups.js',
    '/js/chat/ui.js',
    '/js/chat/views/LoginView.js',
    '/js/chat/views/ChatsView.js',
    '/js/chat/views/ConversationView.js',
    '/js/chat/views/NewChatView.js',
    '/js/chat/views/SettingsView.js',
    '/js/chat/views/GroupsView.js',
    '/js/chat/views/ProfileView.js',
    '/js/chat/views/RelayView.js',
    '/icons/icon-192x192.png',
    'https://cdnjs.cloudflare.com/ajax/libs/ethers/6.7.1/ethers.umd.min.js'
];

// Install event - cache static assets
self.addEventListener('install', (event) => {
    console.log('[SW] Installing...');
    
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then((cache) => {
                console.log('[SW] Caching static assets');
                return cache.addAll(STATIC_ASSETS);
            })
            .then(() => {
                console.log('[SW] Install complete');
                return self.skipWaiting();
            })
            .catch((error) => {
                console.error('[SW] Install failed:', error);
            })
    );
});

// Activate event - clean old caches
self.addEventListener('activate', (event) => {
    console.log('[SW] Activating...');
    
    event.waitUntil(
        caches.keys()
            .then((cacheNames) => {
                return Promise.all(
                    cacheNames
                        .filter((name) => name !== CACHE_NAME)
                        .map((name) => {
                            console.log('[SW] Deleting old cache:', name);
                            return caches.delete(name);
                        })
                );
            })
            .then(() => {
                console.log('[SW] Activation complete');
                return self.clients.claim();
            })
    );
});

// Fetch event - serve from cache, fallback to network
self.addEventListener('fetch', (event) => {
    const { request } = event;
    const url = new URL(request.url);
    
    // Skip non-GET requests
    if (request.method !== 'GET') return;
    
    // Skip WebSocket connections
    if (url.protocol === 'wss:' || url.protocol === 'ws:') return;
    
    // Network-first for API calls
    if (url.pathname.startsWith('/api/')) {
        event.respondWith(
            fetch(request)
                .catch(() => caches.match(request))
        );
        return;
    }
    
    // Cache-first for static assets
    event.respondWith(
        caches.match(request)
            .then((cachedResponse) => {
                if (cachedResponse) {
                    // Update cache in background
                    event.waitUntil(
                        fetch(request)
                            .then((networkResponse) => {
                                if (networkResponse && networkResponse.ok) {
                                    caches.open(CACHE_NAME)
                                        .then((cache) => cache.put(request, networkResponse));
                                }
                            })
                            .catch(() => {})
                    );
                    return cachedResponse;
                }
                
                return fetch(request)
                    .then((networkResponse) => {
                        // Cache new response
                        if (networkResponse && networkResponse.ok) {
                            const responseClone = networkResponse.clone();
                            caches.open(CACHE_NAME)
                                .then((cache) => cache.put(request, responseClone));
                        }
                        return networkResponse;
                    })
                    .catch(() => {
                        // Return offline fallback for navigation
                        if (request.mode === 'navigate') {
                            return caches.match('/chat.html');
                        }
                        return new Response('Offline', { status: 503 });
                    });
            })
    );
});

// Push notification event
self.addEventListener('push', (event) => {
    console.log('[SW] Push received:', event);
    
    let data = {
        title: 'MumbleChat',
        body: 'New message received',
        icon: '/icons/icon-192.svg',
        badge: '/icons/icon-72.svg',
        tag: 'mumblechat-message'
    };
    
    if (event.data) {
        try {
            const payload = event.data.json();
            data = { ...data, ...payload };
        } catch (e) {
            data.body = event.data.text();
        }
    }
    
    event.waitUntil(
        self.registration.showNotification(data.title, {
            body: data.body,
            icon: data.icon,
            badge: data.badge,
            tag: data.tag,
            vibrate: [100, 50, 100],
            data: data.url || '/chat.html',
            actions: [
                { action: 'reply', title: 'Reply' },
                { action: 'dismiss', title: 'Dismiss' }
            ]
        })
    );
});

// Notification click event
self.addEventListener('notificationclick', (event) => {
    console.log('[SW] Notification clicked:', event.action);
    
    event.notification.close();
    
    if (event.action === 'dismiss') return;
    
    event.waitUntil(
        clients.matchAll({ type: 'window', includeUncontrolled: true })
            .then((clientList) => {
                // Focus existing window if available
                for (const client of clientList) {
                    if (client.url.includes('/chat') && 'focus' in client) {
                        return client.focus();
                    }
                }
                
                // Open new window
                if (clients.openWindow) {
                    return clients.openWindow(event.notification.data || '/chat.html');
                }
            })
    );
});

// Background sync for offline messages
self.addEventListener('sync', (event) => {
    console.log('[SW] Background sync:', event.tag);
    
    if (event.tag === 'send-messages') {
        event.waitUntil(sendPendingMessages());
    }
});

async function sendPendingMessages() {
    // Get pending messages from IndexedDB
    // This would be implemented with actual IndexedDB logic
    console.log('[SW] Sending pending messages...');
}

// Message from main thread
self.addEventListener('message', (event) => {
    console.log('[SW] Message received:', event.data);
    
    if (event.data.type === 'SKIP_WAITING') {
        self.skipWaiting();
    }
});
