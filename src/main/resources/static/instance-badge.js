// instance-badge.js — QA/TEST instance indicator, shared by index.html (app)
// and login.html (login). Driven by app.instance.label / app.instance.banner,
// exposed via /api/auth/session. Empty values render nothing (prod).
(function () {
  'use strict';

  var PILL_BG = '#B8342B';    // red pill
  var RIBBON_BG = '#C7801B';  // amber warning ribbon

  function el(tag, css, text) {
    var e = document.createElement(tag);
    if (css) e.style.cssText = css;
    if (text != null) e.textContent = text;
    return e;
  }

  function makePill(label) {
    return el('span',
      'display:inline-flex;align-items:center;gap:5px;height:20px;padding:0 9px;margin-left:8px;' +
      'border-radius:999px;background:' + PILL_BG + ';color:#fff;font-weight:700;font-size:10.5px;' +
      "letter-spacing:.06em;font-family:'IBM Plex Mono',monospace;vertical-align:middle;",
      '● ' + label);
  }

  function dismissKey(version) { return 'plm-qa-ribbon-dismissed-' + (version || 'unknown'); }

  function ribbonDismissed(version) {
    try { return localStorage.getItem(dismissKey(version)) === '1'; } catch (e) { return false; }
  }

  function makeRibbon(text, version, dismissible) {
    var bar = el('div',
      'position:relative;background:' + RIBBON_BG + ';color:#fff;text-align:center;font-weight:600;' +
      "font-size:12px;padding:6px 38px;font-family:'IBM Plex Sans','Segoe UI',Arial,sans-serif;", text);
    if (dismissible) {
      var x = el('button',
        'position:absolute;right:8px;top:50%;transform:translateY(-50%);background:transparent;border:none;' +
        'color:#fff;cursor:pointer;font-size:14px;line-height:1;padding:2px 8px;border-radius:4px;', '✕');
      x.setAttribute('aria-label', 'Dismiss');
      x.onclick = function () {
        try { localStorage.setItem(dismissKey(version), '1'); } catch (e) {}
        if (bar.parentNode) bar.parentNode.removeChild(bar);
      };
      bar.appendChild(x);
    }
    return bar;
  }

  // Public entry point.
  // opts: { label, banner, bannerNonprod, prodData, mode:'app'|'login', version }
  window.renderInstanceIndicator = function (opts) {
    opts = opts || {};
    var label = (opts.label || '').trim();
    // Suffix the browser tab title with the instance label so multiple open tabs
    // (Prod vs QA/Test) are distinguishable at a glance — Vikas Singh, Jul 2026.
    // Prod's label is empty, so the title stays "Agile PLM Toolkit". Idempotent:
    // strips any existing trailing "(...)" before re-appending.
    if (label) {
      document.title = document.title.replace(/\s*\([^()]*\)\s*$/, '') + ' (' + label + ')';
    }
    // Pick the wording: prod-data banner, or the softer non-prod text when this
    // box is pointed at non-prod data (falls back to the prod banner if unset).
    var prodData = opts.prodData !== false; // default true (conservative)
    var bannerNonprod = (opts.bannerNonprod || '').trim();
    var banner = (prodData ? (opts.banner || '') : (bannerNonprod || opts.banner || '')).trim();
    var mode = opts.mode || 'app';
    var version = opts.version || window.appBuildVersion || '';
    if (!label && !banner) return; // prod: render nothing

    if (mode === 'login') {
      if (banner) document.body.insertBefore(makeRibbon(banner, version, false), document.body.firstChild);
      if (label) {
        var brand = document.querySelector('.topbar .brand-mark');
        if (brand) brand.appendChild(makePill(label));
        // Correct the hardcoded green "Production" crumb pill.
        var crumbPill = document.querySelector('.left-pane .crumb .pill');
        if (crumbPill) {
          crumbPill.innerHTML = '<span style="width:6px;height:6px;border-radius:50%;background:' +
            PILL_BG + ';display:inline-block"></span>' + label;
        }
        // Correct the hardcoded "prod" suffix in the topbar version line.
        var tv = document.getElementById('topbarVersion');
        if (tv) tv.innerHTML = tv.innerHTML.replace(/prod/i, label.toLowerCase());
      }
    } else {
      if (banner && !ribbonDismissed(version)) {
        var nav = document.querySelector('.navbar');
        if (nav && nav.parentNode) nav.parentNode.insertBefore(makeRibbon(banner, version, true), nav);
      }
      if (label) {
        var b = document.querySelector('.navbar .brand');
        if (b) b.appendChild(makePill(label));
      }
    }
  };

  // Login page self-loads (no session bootstrap there). Opt in via a body attribute.
  if (document.body && document.body.getAttribute('data-instance-autoload') === 'login') {
    fetch('/api/auth/session', { credentials: 'same-origin', cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        window.renderInstanceIndicator({
          label: d.instanceLabel,
          banner: d.instanceBanner,
          bannerNonprod: d.instanceBannerNonprod,
          prodData: d.instanceProdData,
          mode: 'login'
        });
      })
      .catch(function () { /* server down -> render nothing, same as today */ });
  }
})();
