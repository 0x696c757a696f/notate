// Injected into every page of a web document (R4-2). It
//  1. locks the layout width (<meta viewport>) so the page is scaled to the pane instead of reflowed and ink stays on
//     the words it was written on (a fixed-layout page keeps its own viewport and reports it),
//  2. finds the element that really scrolls the page and reports its scroll position and the content size, so ink follows
//     documents whose body (not the window) scrolls,
//  3. optionally turns animation and smooth scrolling off (reduce motion).
// The element rule must stay in sync with ScrollCandidate.pick in :core:web.
(function () {
  if (window.__notate) return;
  var B = window.NotateBridge;
  var N = (window.__notate = { el: null, width: 0, reduce: false });

  function rootEl() { return document.scrollingElement || document.documentElement; }

  function lockWidth(w) {
    N.width = w;
    var m = document.querySelector('meta[name=viewport]');
    var own = !!(m && !m.__notate && !m.hasAttribute("data-notate") && /width\s*=\s*\d/.test(m.content));
    if (!own) {
      if (!m) {
        m = document.createElement('meta');
        m.name = 'viewport';
        (document.head || document.documentElement).appendChild(m);
      }
      m.__notate = true;
      m.content = 'width=' + w + ', minimum-scale=0.1, maximum-scale=10, user-scalable=yes';
    }
    if (B) B.onLayout(window.innerWidth || w, own);
  }

  function overflows(e) { return e.scrollHeight - e.clientHeight > 1; }

  function pick() {
    var root = rootEl();
    if (overflows(root)) return root;
    var vp = window.innerWidth * window.innerHeight, best = null, bestArea = 0;
    var list = document.body ? document.body.querySelectorAll('*') : [];
    for (var i = 0; i < list.length && i < 600; i++) {
      var e = list[i], oy = getComputedStyle(e).overflowY;
      if (oy !== 'auto' && oy !== 'scroll' && oy !== 'overlay') continue;
      if (!overflows(e)) continue;
      var area = e.clientWidth * e.clientHeight;
      if (area >= vp * 0.25 && area > bestArea) { best = e; bestArea = area; }
    }
    return best || root;
  }

  var raf = 0;
  function schedule() { if (!raf) raf = requestAnimationFrame(report); }
  function report() {
    raf = 0;
    if (!B) return;
    var e = N.el || (N.el = pick());
    var inner = e !== rootEl();
    var w = inner ? e.scrollWidth : Math.max(document.documentElement.scrollWidth, document.body ? document.body.scrollWidth : 0);
    var h = inner ? e.scrollHeight : Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
    // The WebView's own scroll covers the root element; only an inner scroller adds to it.
    B.onScroll(inner ? e.scrollLeft : 0, inner ? e.scrollTop : 0, w, h);
  }

  var repick = 0;
  function onMutation() {
    if (repick) return;
    repick = setTimeout(function () { repick = 0; N.el = pick(); schedule(); }, 400);
  }

  N.setLayout = lockWidth;
  N.scrollTo = function (y) { var e = N.el || pick(); if (e !== rootEl()) e.scrollTop = y; };
  N.setReduceMotion = function (on) {
    N.reduce = !!on;
    var s = document.getElementById('__notate_rm');
    if (!on) { if (s) s.remove(); return; }
    if (s) return;
    s = document.createElement('style');
    s.id = '__notate_rm';
    s.textContent = '*,*::before,*::after{animation-duration:0.001s!important;animation-delay:0s!important;' +
      'animation-iteration-count:1!important;transition-duration:0.001s!important;transition-delay:0s!important;' +
      'scroll-behavior:auto!important}';
    (document.head || document.documentElement).appendChild(s);
  };

  window.addEventListener('scroll', function (ev) {
    if (!N.el || ev.target === N.el || ev.target === document) schedule();
  }, true);
  window.addEventListener('resize', schedule);
  window.addEventListener('hashchange', function () { if (B) B.onNavigate(location.hash || ''); });
  function start() {
    N.el = pick();
    schedule();
    try {
      new MutationObserver(onMutation).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
    } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
