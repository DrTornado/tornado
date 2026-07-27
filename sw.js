// Tornado service worker — enables real app install (not just a shortcut) and offline opening
const CACHE = 'tornado-v66';
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

  // حاسم: لا نعترض إطلاقاً ملفات المحرك الصوتي ونماذج الأصوات (عشرات الميجابايت).
  // اعتراضها كان يستنسخ كل استجابة في الذاكرة ويحاول تخزينها، فيتجاوز حصة التخزين على الجوال
  // ويُفشل جلب ملفات WASM بالكامل. المتصفح يخزّنها بنفسه بكفاءة أعلى بكثير.
  if (url.pathname.includes('/piper/') || url.pathname.includes('/voices/')) return;

  // كذلك نتجاهل أي طلب نطاقي (Range) — تخزين الاستجابات الجزئية (206) غير مسموح ويسبب أخطاء
  if (e.request.headers.has('range')) return;

  e.respondWith(
    fetch(e.request).then(r => {
      // نخزّن فقط الاستجابات الكاملة الناجحة، وضمن حماية من أي خطأ تخزين (حصة ممتلئة مثلاً)
      if (r && r.ok && r.status === 200 && r.type === 'basic') {
        const copy = r.clone();
        caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
      }
      return r;
    }).catch(() => caches.match(e.request).then(m => m || caches.match('./')))
  );
});
