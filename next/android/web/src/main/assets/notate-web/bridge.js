// Injected into every page of a web document (R4-2). It
//  1. locks the layout width (<meta viewport>) so the page is scaled to the pane instead of reflowed and ink stays on
//     the words it was written on (a fixed-layout page keeps its own viewport and reports it),
//  2. finds the element that really scrolls the page and reports its scroll position and the content size, so ink follows
//     documents whose body (not the window) scrolls,
//  3. optionally turns animation and smooth scrolling off (reduce motion),
//  4. pauses the page's running animations while the pane is frozen for writing (setFrozen).
// The element rule must stay in sync with ScrollCandidate.pick in :core:web.
(function () {
  if (window.__notate) return;
  var B = window.NotateBridge;
  var N = (window.__notate = { el: null, width: 0, reduce: false });
  var WALK_MAX = 2000;

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
    if (!document.body) return root;
    // A bounded walk in document order: querySelectorAll('*') would first build a list of the whole tree (huge for pages
    // full of formulas or SVG). The subtrees of svg/math and of elements too small to be the page scroller are skipped:
    // a scroller inside them could not cover a quarter of the viewport either.
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT, {
      acceptNode: function (e) {
        var t = e.localName;
        if (t === 'svg' || t === 'math' || t === 'script' || t === 'style' || t === 'template') return NodeFilter.FILTER_REJECT;
        if (e.clientWidth * e.clientHeight < vp * 0.25 && e.getClientRects().length) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    for (var i = 0, e; i < WALK_MAX && (e = w.nextNode()); i++) {
      if (!overflows(e)) continue;
      var oy = getComputedStyle(e).overflowY;
      if (oy !== 'auto' && oy !== 'scroll' && oy !== 'overlay') continue;
      var area = e.clientWidth * e.clientHeight;
      if (area >= vp * 0.25 && area > bestArea) { best = e; bestArea = area; }
    }
    return best || root;
  }

  /** The scroller found last is still the one: the root scrolls, or the inner scroller is in the page and still scrolls. */
  function stillValid(e) {
    var root = rootEl();
    if (overflows(root)) return e === root;
    return !!e && e !== root && e.isConnected && overflows(e);
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

  var repick = 0, lastActivity = 0;
  function onMutation() {
    // Tell the pane the page is still building itself (math typesetting, lazy content), at most twice a second: it waits
    // for the page to go quiet before freezing it for writing.
    var now = Date.now();
    if (B && B.onActivity && now - lastActivity > 500) { lastActivity = now; B.onActivity(); }
    if (repick) return;
    // Script-driven pages mutate all the time; look for a new scroller only when the current one stopped being it.
    repick = setTimeout(function () { repick = 0; if (!stillValid(N.el)) N.el = pick(); schedule(); }, 400);
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

  // Write mode freezes this page alone (the process-wide WebView timer pause needs every open web pane to agree): its
  // running animations (CSS animations and transitions, element.animate) are paused, and only those are resumed later.
  var frozenAnims = null;
  N.setFrozen = function (on) {
    if (on) {
      if (frozenAnims || !document.getAnimations) return;
      frozenAnims = document.getAnimations().filter(function (a) { return a.playState === 'running'; });
      frozenAnims.forEach(function (a) { try { a.pause(); } catch (e) {} });
    } else if (frozenAnims) {
      frozenAnims.forEach(function (a) { try { if (a.playState === 'paused') a.play(); } catch (e) {} });
      frozenAnims = null;
    }
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
