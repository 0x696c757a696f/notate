// Injected into every document page. Does two things:
//  1. Locks the layout width (<meta viewport>), so the page is scaled to the pane instead of reflowed
//     and ink stays on the words it was written on. Fixed-layout pages keep their own viewport.
//  2. Finds the element that really scrolls the page and reports its scroll position to the app,
//     so ink follows documents whose body (not the window) scrolls.
// The element rule below must stay in sync with ScrollElementPicker.kt.
(function () {
  if (window.__notate) return;
  var B = window.NotateBridge;
  var N = (window.__notate = { el: null, width: 0 });

  function rootEl() { return document.scrollingElement || document.documentElement; }

  function lockWidth(w) {
    N.width = w;
    var m = document.querySelector('meta[name=viewport]');
    if (m && !m.__notate && /width\s*=\s*\d/.test(m.content)) return; // fixed layout: own viewport
    if (!m) {
      m = document.createElement('meta');
      m.name = 'viewport';
      (document.head || document.documentElement).appendChild(m);
    }
    m.__notate = true;
    m.content = 'width=' + w + ', minimum-scale=0.1, maximum-scale=5, user-scalable=yes';
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
    if (!best && document.body && overflows(document.body) && document.body !== root) best = document.body;
    return best || root;
  }

  var raf = 0;
  function schedule() { if (!raf) raf = requestAnimationFrame(report); }
  function report() {
    raf = 0;
    if (!B) return;
    var e = N.el || (N.el = pick());
    var inner = e !== rootEl();
    B.onScroll(inner ? e.scrollLeft : 0, inner ? e.scrollTop : 0, inner);
  }

  var repick = 0;
  function onMutation() {
    if (repick) return;
    repick = setTimeout(function () { repick = 0; N.el = pick(); schedule(); }, 400);
  }

  N.setLayout = lockWidth;
  N.scrollTo = function (y) { var e = N.el || pick(); if (e !== rootEl()) e.scrollTop = y; };

  window.addEventListener('scroll', function (ev) {
    if (!N.el || ev.target === N.el || ev.target === document) schedule();
  }, true);
  window.addEventListener('resize', schedule);
  function start() {
    N.el = pick();
    schedule();
    try {
      new MutationObserver(onMutation).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
    } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
