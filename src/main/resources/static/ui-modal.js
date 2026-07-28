// =====================================================================
// PT-60: shared in-page modal helpers (replaces window.confirm/alert/prompt).
//
// The native browser dialogs render the server hostname ("uls-ep-aglipccb:8090
// says") in their title bar — looks like an unprofessional dev artefact in
// front of business users. These helpers render a SanDisk-styled modal with a
// "PT" mark in the header (matching the icon used in our outbound emails) and
// no host leakage. All three return Promises:
//
//   appAlert(message, opts)           -> Promise<void>     (single OK button)
//   appConfirm(message, opts)         -> Promise<boolean>  (OK + Cancel)
//   appPrompt(message, defaultValue, opts) -> Promise<string|null>
//
// opts is optional and accepts:
//   - title       : header text (default: "PLM Toolkit")
//   - okText      : OK button label (default: "OK")
//   - cancelText  : Cancel button label (default: "Cancel")
//   - danger      : if true, the OK button is rendered red (for destructive ops)
//   - placeholder : (prompt only) input placeholder
//
// Esc and click-outside both resolve as Cancel (or void for alert).
// =====================================================================

(function () {
  var openStack = []; // for nested-modal Esc handling

  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function ptMark() {
    // 24x24 dark rounded square with "PT" — same shape used in resolve-email headers.
    return '<div style="width:24px;height:24px;background:#0F1720;color:#fff;font-size:10px;font-weight:700;'
      + 'text-align:center;line-height:24px;border-radius:5px;font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;'
      + 'flex:0 0 24px;">PT</div>';
  }

  function buildShell(opts, contentHtml, footerHtml) {
    var title = (opts && opts.title) || 'PLM Toolkit';
    var overlay = document.createElement('div');
    overlay.className = 'ui-modal-overlay';
    overlay.style.cssText = 'position:fixed;inset:0;background:rgba(15,23,32,0.55);z-index:20000;'
      + 'display:flex;align-items:center;justify-content:center;padding:20px;'
      + 'font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;';

    var card = document.createElement('div');
    card.className = 'ui-modal-card';
    card.style.cssText = 'background:#ffffff;max-width:520px;width:100%;border-radius:8px;'
      + 'border:1px solid #E8E6DF;box-shadow:0 10px 40px rgba(0,0,0,0.18);overflow:hidden;';

    // Header: PT mark + title (no hostname banner).
    var header = '<div style="display:flex;align-items:center;gap:10px;padding:14px 18px;border-bottom:1px solid #E8E6DF;">'
      + ptMark()
      + '<div style="font-size:13px;font-weight:600;color:#0F1720;">' + esc(title) + '</div>'
      + '</div>';

    card.innerHTML = header
      + '<div class="ui-modal-body" style="padding:18px 20px;font-size:14px;line-height:1.5;color:#0F1720;">'
      + contentHtml
      + '</div>'
      + '<div class="ui-modal-footer" style="display:flex;justify-content:flex-end;gap:8px;padding:12px 18px;border-top:1px solid #E8E6DF;background:#FAFAF7;">'
      + footerHtml
      + '</div>';

    overlay.appendChild(card);
    return overlay;
  }

  function okBtn(opts, label) {
    var danger = opts && opts.danger;
    var bg = danger ? '#B8342B' : '#0F1720';
    return '<button class="ui-modal-ok" style="background:' + bg + ';color:#fff;border:none;'
      + 'padding:8px 18px;border-radius:6px;font-size:13px;font-weight:600;cursor:pointer;">'
      + esc(label || 'OK') + '</button>';
  }

  function cancelBtn(label) {
    return '<button class="ui-modal-cancel" style="background:#fff;color:#6B7280;'
      + 'border:1px solid #E8E6DF;padding:8px 18px;border-radius:6px;font-size:13px;font-weight:500;cursor:pointer;">'
      + esc(label || 'Cancel') + '</button>';
  }

  function close(overlay) {
    if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay);
    var idx = openStack.indexOf(overlay);
    if (idx >= 0) openStack.splice(idx, 1);
  }

  function wireGlobalEsc() {
    // One global listener routes Esc to the top-most open modal.
    if (window.__uiModalEscWired) return;
    window.__uiModalEscWired = true;
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape') return;
      var top = openStack[openStack.length - 1];
      if (top && typeof top.__uiCancel === 'function') {
        e.stopPropagation();
        top.__uiCancel();
      }
    }, true);
  }

  function present(overlay, onOk, onCancel) {
    wireGlobalEsc();
    document.body.appendChild(overlay);
    openStack.push(overlay);
    overlay.__uiCancel = function () { close(overlay); if (onCancel) onCancel(); };

    // Click outside the card cancels.
    overlay.addEventListener('click', function (e) {
      if (e.target === overlay) overlay.__uiCancel();
    });

    var okEl = overlay.querySelector('.ui-modal-ok');
    var cancelEl = overlay.querySelector('.ui-modal-cancel');
    if (okEl) {
      okEl.addEventListener('click', function () { close(overlay); if (onOk) onOk(); });
      // Default focus on OK so Enter confirms.
      setTimeout(function () { okEl.focus(); }, 0);
    }
    if (cancelEl) {
      cancelEl.addEventListener('click', function () { overlay.__uiCancel(); });
    }
  }

  /**
   * Promise-based replacement for window.alert. Fire-and-forget is fine; awaiting
   * is optional. Multi-line messages with `\n` render as separate paragraphs.
   */
  window.appAlert = function (message, opts) {
    return new Promise(function (resolve) {
      var body = String(message == null ? '' : message)
        .split(/\r?\n/)
        .map(function (line) { return '<div>' + esc(line || '\u00a0') + '</div>'; })
        .join('');
      var overlay = buildShell(opts, body, okBtn(opts));
      present(overlay, function () { resolve(); }, function () { resolve(); });
    });
  };

  /**
   * Promise-based replacement for window.confirm. Resolves true on OK,
   * false on Cancel / Esc / click-outside. Caller wraps with .then(ok => ...).
   * For destructive operations pass { danger: true } to make OK red.
   */
  window.appConfirm = function (message, opts) {
    return new Promise(function (resolve) {
      var body = String(message == null ? '' : message)
        .split(/\r?\n/)
        .map(function (line) { return '<div>' + esc(line || '\u00a0') + '</div>'; })
        .join('');
      var footer = cancelBtn(opts && opts.cancelText) + okBtn(opts, (opts && opts.okText) || 'OK');
      var overlay = buildShell(opts, body, footer);
      present(overlay,
        function () { resolve(true); },
        function () { resolve(false); });
    });
  };

  /**
   * Promise-based replacement for window.prompt. Resolves with the string value
   * on OK or null on Cancel/Esc/click-outside. defaultValue is the pre-fill.
   */
  window.appPrompt = function (message, defaultValue, opts) {
    return new Promise(function (resolve) {
      var pl = (opts && opts.placeholder) ? esc(opts.placeholder) : '';
      var def = defaultValue == null ? '' : String(defaultValue);
      var body =
        '<div style="margin-bottom:10px;">' + esc(message == null ? '' : message) + '</div>'
        + '<input type="text" class="ui-modal-input" value="' + esc(def) + '" placeholder="' + pl + '"'
        + ' style="width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #E8E6DF;border-radius:6px;font-size:14px;'
        + 'font-family:inherit;color:#0F1720;background:#fff;">';
      var footer = cancelBtn(opts && opts.cancelText) + okBtn(opts, (opts && opts.okText) || 'OK');
      var overlay = buildShell(opts, body, footer);
      var input = overlay.querySelector('.ui-modal-input');

      function finishOk() {
        var v = input ? input.value : '';
        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        var idx = openStack.indexOf(overlay);
        if (idx >= 0) openStack.splice(idx, 1);
        resolve(v);
      }

      // Override OK so we capture the input value before close+resolve.
      // present() already binds OK to close+resolve(true) for confirm; here we
      // re-bind to capture the input first.
      present(overlay,
        function () { /* will be overridden below */ },
        function () { resolve(null); });
      var okEl = overlay.querySelector('.ui-modal-ok');
      if (okEl) {
        // Replace the present()-installed click handler.
        var fresh = okEl.cloneNode(true);
        okEl.parentNode.replaceChild(fresh, okEl);
        fresh.addEventListener('click', finishOk);
        setTimeout(function () { fresh.focus(); }, 0);
      }
      if (input) {
        setTimeout(function () { input.focus(); input.select(); }, 0);
        input.addEventListener('keydown', function (e) {
          if (e.key === 'Enter') { e.preventDefault(); finishOk(); }
        });
      }
    });
  };
})();
