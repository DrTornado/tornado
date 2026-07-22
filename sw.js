// Tornado service worker — enables real app install (not just a shortcut) and offline opening
const CACHE = 'tornado-v22';
const PRECACHE = ['./', './manifest.json', './icon-192.png', './icon-512.png', './icon-180.png', './favicon-32.png'];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c =>
      // نحمّل كل ملف على حدة: فشل ملف واحد لا يوقف التثبيت كاملاً
      Promise.all(PRECACHE.map(url => c.add(url).catch(() => {})))
    ).then(() => self.skipWaiting())
  );
});
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return; // طلبات القواميس والترجمة تمر مباشرة للشبكة
  e.respondWith(
    fetch(e.request).then(r => {
      const copy = r.clone();
      caches.open(CACHE).then(c => c.put(e.request, copy));
      return r;
    }).catch(() => caches.match(e.request).then(m => m || caches.match('./')))
  );
});
