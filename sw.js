// Tornado service worker — enables real app install (not just a shortcut) and offline opening
const CACHE = 'tornado-v89';
const PRECACHE = ['./', './manifest.json', './icon-192.png', './icon-512.png', './icon-180.png', './favicon-32.png'];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c =>
      // نحمّل كل ملف على حدة: فشل ملف واحد لا يوقف التثبيت كاملاً
      Promise.all(PRECACHE.map(url => c.add(url).catch(() => {})))
    ).then(() => self.skipWaiting())
  );
});
/*
 * النسخة الجديدة تُحمّل نفسها على الصفحات المفتوحة.
 *
 * المصيدة التي وقعنا فيها: الإصلاح الذي يمنع بقاء المستخدم على نسخة قديمة
 * يعيش داخل النسخة الجديدة — ونسخته القديمة هي التي تمنعه من الوصول إليه.
 * فيُنشر إصلاح تلو إصلاح ولا يصله شيء، ويجرّب فيجد العطل نفسه، ويظنّ أن لا
 * أحد أصلح شيئاً. وقد ظنّ.
 *
 * والعامل يملك ما لا تملكه الصفحة: أن يعيد تحميلها من الخارج. فينكسر الطوق
 * من هنا مرّةً واحدة — المتصفّح يفحص هذا الملف عند كل انتقال متجاوزاً كل
 * ذاكرة، فما إن تُنشر نسخة حتى تُفعّل وتُحدّث كل صفحة مفتوحة بنفسها.
 *
 * والتفعيل يقع مرّة واحدة لكل نسخة، فلا حلقة إعادة تحميل لا تنتهي.
 */
self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)));
    // هل كنّا نستبدل نسخةً سابقة، أم هذا أول تثبيت؟ الأول وحده يستحق إعادة تحميل
    const had = keys.some(k => k !== CACHE && k.startsWith('tornado-'));
    await self.clients.claim();
    if (!had) return;
    const clients = await self.clients.matchAll({ type: 'window' });
    await Promise.all(clients.map(c => c.navigate(c.url).catch(() => {})));
  })());
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

  /*
   * الصفحة نفسها تُجلب متجاوزةً ذاكرة المتصفّح، لا ذاكرتنا وحدها.
   *
   * هذا العامل يجلب من الشبكة أولاً، لكن `fetch` العادي يمرّ بذاكرة HTTP في
   * المتصفّح — وGitHub Pages تضع عمراً بعشر دقائق. فيُنشر إصلاح ويبقى المستخدم
   * على النسخة القديمة عشر دقائق بلا أن يعلم أحد لماذا لم يتغيّر شيء. حدث هذا
   * فعلاً: نُشرت نسخة ولم تظهر دوالّها في الصفحة المحمّلة.
   *
   * والتجاوز للمستند وحده: الصور والأيقونات لا تستحق إبطال ذاكرتها.
   */
  const isDoc = e.request.mode === 'navigate'
    || url.pathname.endsWith('/') || url.pathname.endsWith('.html');
  const req = isDoc ? new Request(e.request, { cache: 'reload' }) : e.request;

  e.respondWith(
    fetch(req).then(r => {
      // نخزّن فقط الاستجابات الكاملة الناجحة، وضمن حماية من أي خطأ تخزين (حصة ممتلئة مثلاً)
      if (r && r.ok && r.status === 200 && r.type === 'basic') {
        const copy = r.clone();
        caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
      }
      return r;
    }).catch(() => caches.match(e.request).then(m => m || caches.match('./')))
  );
});
