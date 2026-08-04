// Tornado service worker — enables real app install (not just a shortcut) and offline opening
const CACHE = 'tornado-v105';
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
 * التفعيل ينظّف ويستولي، ولا يعيد تحميل شيئاً.
 *
 * جرّبت أن يعيد العامل تحميل الصفحات المفتوحة ليصل الإصلاح في فتحة واحدة بدل
 * فتحتين — وتراجعت: إعادة تحميل من الخارج تقع على مستخدم قد يكون يكتب ملاحظة
 * فتضيع كتابته. مكسبُ فتحةٍ واحدة لا يساوي فقدان نصّ.
 *
 * والاستيلاء مع جلب المستند متجاوزاً ذاكرة المتصفّح (أدناه) يكفيان: ما إن
 * يُفعَّل هذا العامل حتى تكون الفتحة التالية على أحدث نسخة دائماً.
 */
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

  // ملف الإصدار لا يُعترض ولا يُخزَّن أبداً — هو الحَكَم الذي يكشف أننا على نسخة قديمة
  if (url.pathname.endsWith('version.json')) return;

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
