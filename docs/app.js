(function () {
  'use strict';
  var d = document, html = d.documentElement;
  var TITLES = { tr: 'Veilo Authenticator — Kod ekranda. Anahtar asla.', en: 'Veilo Authenticator — The code is on screen. The key never is.' };

  /* ---------- language ---------- */
  function setLang(l) {
    html.setAttribute('data-lang', l); html.lang = l; d.title = TITLES[l];
    try { localStorage.setItem('veilo-lang', l); } catch (e) {}
    if (window.ScrollTrigger) window.ScrollTrigger.refresh();
  }
  setLang(html.getAttribute('data-lang') === 'en' ? 'en' : 'tr');
  d.getElementById('lang').addEventListener('click', function () {
    setLang(html.getAttribute('data-lang') === 'tr' ? 'en' : 'tr');
  });

  /* ---------- Ürünler menüsü: dışarı tıklayınca / Esc ile kapan ---------- */
  var pm = d.querySelector('.prod-menu');
  if (pm) {
    d.addEventListener('click', function (e) { if (pm.open && !pm.contains(e.target)) pm.open = false; });
    d.addEventListener('keydown', function (e) { if (e.key === 'Escape' && pm.open) { pm.open = false; pm.querySelector('summary').focus(); } });
  }


  /* ---------- pusula (sayfa içi hızlı gezinme) ---------- */
  var qn = d.getElementById('qn'), qb = d.getElementById('qn-btn');
  function qOpen(v) { qn.classList.toggle('open', v); qb.setAttribute('aria-expanded', v ? 'true' : 'false'); }
  qb.addEventListener('click', function (e) { e.stopPropagation(); qOpen(!qn.classList.contains('open')); });

  /* pusula: sayfanın ne kadarı okundu (axon ile aynı) */
  var qring = d.getElementById('qn-ring');
  if (qring) { var qrs = function () { var m = d.documentElement.scrollHeight - innerHeight; qring.style.strokeDashoffset = (144.5 * (1 - Math.min(1, (window.scrollY || 0) / Math.max(1, m)))).toFixed(1); }; addEventListener('scroll', qrs, { passive: true }); addEventListener('resize', qrs); qrs(); }
  d.addEventListener('click', function () { qOpen(false); });
  d.addEventListener('keydown', function (e) { if (e.key === 'Escape') qOpen(false); });
  qn.querySelectorAll('a').forEach(function (a) { a.addEventListener('click', function () { qOpen(false); }); });

  /* ---------- live TOTP (RFC 6238 test key, SHA-1, 6 digits, 30 s) ---------- */
  var codeEls = d.querySelectorAll('[data-code]'), secEls = d.querySelectorAll('[data-secs]'), bars = d.querySelectorAll('[data-bar]');
  var keyP = (window.crypto && crypto.subtle) ? crypto.subtle.importKey('raw', new TextEncoder().encode('12345678901234567890'), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']) : null;
  function totp(counter) {
    var buf = new ArrayBuffer(8), view = new DataView(buf);
    view.setUint32(0, Math.floor(counter / 4294967296)); view.setUint32(4, counter >>> 0);
    return keyP.then(function (k) { return crypto.subtle.sign('HMAC', k, buf); }).then(function (sig) {
      var s = new Uint8Array(sig), o = s[19] & 15;
      var n = ((s[o] & 127) << 24 | s[o + 1] << 16 | s[o + 2] << 8 | s[o + 3]) % 1000000;
      return ('000000' + n).slice(-6);
    });
  }
  function render(c) {
    var txt = c.slice(0, 3) + ' ' + c.slice(3);
    codeEls.forEach(function (el) {
      el.innerHTML = txt.split('').map(function (ch, i) { return '<span class="dg" style="--i:' + i + '">' + ch + '</span>'; }).join('');
      el.setAttribute('aria-label', c);
      el.classList.remove('flip'); void el.offsetWidth; el.classList.add('flip');
    });
  }
  var lastCtr = -1;
  function tick() {
    var now = Date.now() / 1000, ctr = Math.floor(now / 30), left = 30 - (now % 30);
    secEls.forEach(function (e) { e.textContent = Math.ceil(left); });
    bars.forEach(function (b) { b.style.transform = 'scaleX(' + (left / 30).toFixed(3) + ')'; });
    if (ctr !== lastCtr && keyP) { lastCtr = ctr; totp(ctr).then(render).catch(function () {}); }
  }
  tick(); setInterval(tick, 250);

  /* ---------- motion ---------- */
  if (html.classList.contains('rm') || !window.gsap || !window.ScrollTrigger || !window.Lenis) { html.classList.add('rm'); return; }
  var gsap = window.gsap, ST = window.ScrollTrigger;
  gsap.registerPlugin(ST);
  ST.config({ ignoreMobileResize: true });

  // Smooth wheel scroll only; touch keeps native momentum (syncTouch: false).
  var lenis = new window.Lenis({ lerp: 0.1, wheelMultiplier: 1, smoothWheel: true, syncTouch: false, anchors: false });
  lenis.on('scroll', ST.update);
  gsap.ticker.add(function (t) { lenis.raf(t * 1000); });
  gsap.ticker.lagSmoothing(0);

  // Anchor links: smooth, offset for fixed nav, keep URL hash.
  d.querySelectorAll('a[href^="#"]').forEach(function (a) {
    a.addEventListener('click', function (e) {
      var id = a.getAttribute('href');
      if (id === '#top') { e.preventDefault(); lenis.scrollTo(0, { duration: 1.4 }); return; }
      var t = d.querySelector(id); if (!t) return;
      e.preventDefault();
      lenis.scrollTo(t, { offset: id === '#nasil' ? 0 : -64, duration: 1.4 });
      history.replaceState(null, '', id);
    });
  });

  // Nav: hides while scrolling down, returns on scroll up.
  var nav = d.querySelector('.nav');
  lenis.on('scroll', function (e) {
    nav.classList.toggle('solid', e.scroll > 24);
    nav.classList.toggle('hide', e.direction === 1 && e.scroll > 240);
  });

  // Hero intro
  gsap.from('.hero .in', { yPercent: 105, duration: 1.2, ease: 'expo.out', stagger: 0.09, delay: 0.1 });
  gsap.from('.hero .fade', { y: 22, autoAlpha: 0, duration: 1, ease: 'power3.out', stagger: 0.08, delay: 0.35 });
  gsap.from('.hero .phone', { y: 80, rotate: 3, autoAlpha: 0, duration: 1.5, ease: 'expo.out', delay: 0.2 });
  gsap.from('.hero .live', { y: 30, autoAlpha: 0, duration: 1.2, ease: 'expo.out', delay: 0.6 });
  gsap.to('.hero .phone', { yPercent: -8, ease: 'none', scrollTrigger: { trigger: '.hero', start: 'top top', end: 'bottom top', scrub: true } });

  /* ---------- the descent: pinned, scrubbed, snaps to each layer ---------- */
  var mm = gsap.matchMedia();
  mm.add({ wide: '(min-width: 821px)', narrow: '(max-width: 820px)' }, function (ctx) {
    var k = ctx.conditions.wide ? 1 : 0.6;   // depth factor
    var steps = gsap.utils.toArray('.step');
    var L = { screen: '.l-screen', app: '.l-app', vault: '.l-vault', chip: '.l-chip' };
    gsap.set(steps, { autoAlpha: 0, y: 24 }); gsap.set(steps[0], { autoAlpha: 1, y: 0 });
    gsap.set(L.screen, { z: 3 }); gsap.set(L.app, { z: 2 }); gsap.set(L.vault, { z: 1 }); gsap.set(L.chip, { z: 0 });
    gsap.set('.stack', { rotateX: 0, rotateZ: 0, scale: 1 });

    function swap(tl, from, to, pos) {
      tl.to(steps[from], { autoAlpha: 0, y: -24, duration: 0.3 }, pos)
        .fromTo(steps[to], { autoAlpha: 0, y: 24 }, { autoAlpha: 1, y: 0, duration: 0.35, immediateRender: false }, '>-0.05');
    }

    var tl = gsap.timeline({
      defaults: { ease: 'power2.inOut' },
      scrollTrigger: {
        trigger: '.descent', start: 'top top', end: '+=320%', pin: true, scrub: 0.8,
        snap: { snapTo: 'labels', duration: { min: 0.25, max: 0.8 }, delay: 0.1, ease: 'power2.inOut' }
      }
    });
    tl.addLabel('s0')
      // 1 — tilt the phone open into an exploded view
      .to('.stack', { rotateX: 56, rotateZ: -32, scale: 0.86, duration: 1 }, 's0+=0.1')
      .to(L.screen, { z: 210 * k, duration: 1 }, '<')
      .to(L.app, { z: 70 * k, duration: 1 }, '<')
      .to(L.vault, { z: -70 * k, duration: 1 }, '<')
      .to(L.chip, { z: -210 * k, duration: 1 }, '<');
    swap(tl, 0, 1, '<+=0.45');
    tl.to(L.screen, { autoAlpha: 0.15, z: 320 * k, duration: 0.6 }, '<')
      .addLabel('s1')
      // 2 — down to the vault
      .to(L.app, { autoAlpha: 0.15, z: 260 * k, duration: 0.8 }, '+=0.25')
      .to(L.screen, { autoAlpha: 0, z: 420 * k, duration: 0.8 }, '<')
      .to('.descent', { '--bg': '#1A2338', '--fg': '#F4F3EF', duration: 0.8 }, '<');
    swap(tl, 1, 2, '<+=0.2');
    tl.addLabel('s2')
      // 3 — down to the chip; it rises to meet you
      .to(L.vault, { autoAlpha: 0, z: 260 * k, duration: 0.8 }, '+=0.25')
      .to(L.app, { autoAlpha: 0, z: 400 * k, duration: 0.8 }, '<')
      .to(L.chip, { z: 0, scale: 1.08, duration: 0.8 }, '<')
      .to('.stack', { rotateX: 30, rotateZ: -12, duration: 0.8 }, '<')
      .to('.descent', { '--bg': '#0D1424', duration: 0.8 }, '<')
      .to('.chip .chip-core', { fill: '#5E72A8', duration: 0.5 }, '<+=0.3');
    swap(tl, 2, 3, '<-0.1');
    tl.addLabel('s3').to({}, { duration: 0.25 });
    return function () { gsap.set(steps, { clearProps: 'all' }); };
  });

  /* ---------- principles: words fill in as you read ---------- */
  gsap.utils.toArray('.big').forEach(function (el) {
    el.querySelectorAll('[data-l]').forEach(function (s) {
      s.innerHTML = s.textContent.split(' ').map(function (w) { return '<span class="w">' + w + '</span>'; }).join(' ');
    });
    gsap.fromTo(el.querySelectorAll('.w'), { opacity: 0.12 }, {
      opacity: 1, stagger: 0.15, ease: 'none',
      scrollTrigger: { trigger: el, start: 'top 88%', end: 'top 50%', scrub: true }
    });
    gsap.from(el.parentNode.querySelector('.proof'), { y: 16, autoAlpha: 0, duration: 0.9, ease: 'power3.out', scrollTrigger: { trigger: el, start: 'top 70%' } });
  });

  /* ---------- number matching ---------- */
  gsap.timeline({ scrollTrigger: { trigger: '.match-visual', start: 'top 75%', end: 'center 45%', scrub: 0.6 } })
    .from('.login', { x: -40, autoAlpha: 0, duration: 1 })
    .from('.match .phone', { x: 40, autoAlpha: 0, duration: 1 }, '<')
    .to('.target', { color: '#1E7F52', duration: 0.3 })
    .fromTo('.ring', { scale: 1.9, autoAlpha: 0 }, { scale: 1, autoAlpha: 1, duration: 0.6, ease: 'back.out(2)' }, '<')
    .fromTo('.stamp', { scale: 1.5, autoAlpha: 0, rotate: -10 }, { scale: 1, autoAlpha: 1, rotate: -4, duration: 0.5, ease: 'back.out(2.5)' }, '+=0.15');

  /* ---------- generic reveals ---------- */
  gsap.utils.toArray('[data-reveal]').forEach(function (el) {
    gsap.from(el, { y: 36, autoAlpha: 0, duration: 1.1, ease: 'expo.out', scrollTrigger: { trigger: el, start: 'top 90%' } });
  });

  /* ---------- final code grows into view ---------- */
  gsap.fromTo('.code-xl', { scale: 0.82, autoAlpha: 0.4 }, { scale: 1, autoAlpha: 1, ease: 'none', scrollTrigger: { trigger: '.final', start: 'top bottom', end: 'top 30%', scrub: true } });

  if (d.fonts && d.fonts.ready) d.fonts.ready.then(function () { ST.refresh(); });
  window.addEventListener('load', function () { ST.refresh(); });
  if (location.hash) { var h = d.querySelector(location.hash); if (h) setTimeout(function () { lenis.scrollTo(h, { immediate: true, offset: -64 }); }, 60); }
})();
