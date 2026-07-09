// ===============================================================================
//  imsreview-dco-form.js — DCO-form drawer (single-page tabular form, Jun 2026)
//
//  The previous 4-step wizard has been flattened into ONE scrollable two-column
//  tabular form: a reference panel on top, then 6 numbered collapsible sections
//  (01 Change details, 02 Scope, 03 People, 04 IMS Document — Edit [stub],
//  05 Documents, 06 Review & sign), and a footer status line + Sign & submit.
//  The submit payload + every endpoint used (dco-form-metadata, user-search,
//  ad-search, validate-dco-form, submit-dco) is UNCHANGED — collectForm()
//  returns the exact same object shape the server has always seen. This is a
//  UI restructure only.
//
//  State (window.DCO):
//    meta              — { priority: [...], productLines: [...], ... } from server
//    selectedUsers     — { documentOwners: [{loginId,displayName,email}], ... }
//    selectedValues    — { productLines: ["v1","v2"], subcontractors: [...] }
//    files             — { redline, final, email, other }
// ===============================================================================
(function () {
  'use strict';

  var DCO = {
    open: false,
    token: null,
    info: null,
    meta: null,
    userCache: {},
    // Persisted form state. The whole form lives on one page now, but a few
    // controls (file slots, CID rows, product-line mode) re-render in place, so
    // the text fields are still mirrored into state on every input.
    form: {
      priority: '',
      descriptionOfChange: '',
      reasonForChange: '',
      trainingRequirement: '',
      businessUnit: '',
      changeImpactDisposition: '',
      changeImpactDetails: '',
      changeImpactRows: [],
      // §04 SDSM IMS Document — Edit scalars (single-select attributes).
      varType: '',
      pmChecklist: '',
      notifyStakeholders: '',
      username: '',
      password: '',
      attested: true
    },
    selectedUsers: { documentOwners: [], approvers: [], observers: [] },
    // productGroups / specs / products are §04 multi-pick / typeahead selections.
    selectedValues: { productLines: [], subcontractors: [], productGroups: [], specs: [], products: [] },
    // 'specific' = user picks one or more product lines from the catalog;
    // 'none' = no product line touched, server gets 'N/A' (a valid Agile value).
    productLineMode: 'specific',
    // Tracks which Scope fields were auto-filled from the document, so the form
    // can show a "suggested — you can clear it" note next to them.
    docPrefill: { productLine: false, subcontractor: false },
    files: { redline: null, final: null, email: [], other: []},
    // URL of the self-study training guide; set by the host page (imsreview.js /
    // ims-respond.html) from the IMS-review admin data payload. Empty = no link.
    trainingDocUrl: '',
    submitting: false
  };
  window.DCO = DCO;

  function esc(s) {
    return (s == null ? '' : String(s)).replace(/[&<>"']/g, function (c) {
      return { '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c];
    });
  }
  function $(id) { return document.getElementById(id); }

  // Build an Agile webclient deep-link for a created DCO (change) number;
  // fail-soft to escaped plain text when no base URL is available.
  function dcoAgileChangeLink(number) {
    if (!number) return '';
    var base = (DCO.meta && DCO.meta.agileWebclientUrl) || window.AGILE_WEBCLIENT_URL || '';
    if (!base) return esc(number);
    var href = base + '/object/' + encodeURIComponent((String(number).split('-')[0] || '')) + '/' + encodeURIComponent(number);
    return '<a href="' + href + '" target="_blank" rel="noopener" '
         + 'style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;">' + esc(number) + '</a>';
  }

  // -------------------------------------------------------------------------
  // Open / close
  // -------------------------------------------------------------------------
  window.dcoOpen = function (info, action) {
    // 'OBS' renders a trimmed form (no IMS-Document-Edit / Documents sections);
    // anything else is the standard change-order form. (Vikas Singh 2026-06-24.)
    DCO.action = (action || (info && info.action) || 'UPLOAD');
    DCO.token = (new URLSearchParams(window.location.search)).get('token');
    DCO.info = info;
    DCO.preview = !!(info && info.preview);   // admin layout preview — submit disabled
    DCO.open = true;
    DCO.selectedUsers = { documentOwners: [], approvers: [], observers: [] };
    DCO.selectedValues = { productLines: [], subcontractors: [], productGroups: [], specs: [], products: [] };
    DCO._productDesc = {};
    DCO.productLineMode = 'specific';
    DCO.docPrefill = { productLine: false, subcontractor: false };
    DCO.files = { redline: null, final: null, email: [], other: []};
    // Self-study training-guide link shown under Training Requirement. Prefer an
    // explicit value the host page set on DCO.trainingDocUrl (e.g. imsreview.js
    // from the admin data payload); otherwise fall back to the token/info object.
    // Empty = the link simply doesn't render (the §02 row guards on it).
    DCO.trainingDocUrl = DCO.trainingDocUrl || (info && info.trainingDocUrl) || '';
    DCO.form = {
      priority: '', descriptionOfChange: '', reasonForChange: '',
      trainingRequirement: '', businessUnit: '',
      changeImpactDisposition: '', changeImpactDetails: '', changeImpactRows: [],
      varType: '', pmChecklist: '',
      notifyStakeholders: '',
      // Only pre-fill the sign-off username when the token went to a SINGLE owner.
      // For multi-owner sends every owner gets the same token, so pre-filling the
      // first owner made a co-owner (e.g. Vikas Singh) sign as the first owner
      // (Vikas Jindal) unless they noticed — leave it blank so the actual signer
      // types their own AD username. Mirrors the simple sign page's logic.
      username: (info && info.recipientEmail
                 && !(info.allowedActors && info.allowedActors.length > 1)) ? info.recipientEmail : '',
      password: '', attested: true
    };

    $('dcoBackdrop').classList.add('show');
    $('dcoDrawer').classList.add('show');
    $('dcoDrawer').setAttribute('aria-hidden', 'false');
    // Title + eyebrow. OBS gets a dedicated title/eyebrow; everything else is
    // the standard "Create the change order". Set explicitly on both branches so
    // reopening the reused drawer never carries a stale OBS title.
    var titleEl = $('dcoTitle');
    if (DCO.preview) {
      $('dcoEyebrow').textContent = 'PREVIEW · read-only · submit disabled';
    } else if (DCO.action === 'OBS') {
      if (titleEl) titleEl.textContent = "OBS - Don't need it";
      if (info && info.docNumber) $('dcoEyebrow').textContent = 'Obsolete · ' + info.docNumber;
    } else {
      if (titleEl) titleEl.textContent = 'Create the change order';
      if (info && info.docNumber) $('dcoEyebrow').textContent = 'Update · ' + info.docNumber;
    }
    // Training Guide button in the header strip (handoff §B3) — links to the
    // self-study guide; only shown when a URL is available.
    var tgBtn = $('dcoTrainingBtn');
    if (tgBtn) {
      if (DCO.trainingDocUrl) {
        tgBtn.href = DCO.trainingDocUrl;
        tgBtn.style.display = 'inline-block';
      } else {
        tgBtn.style.display = 'none';
      }
    }
    $('dcoFormContent').textContent = 'Loading form…';
    fetchMetadataThenRender();
    if (DCO.preview) return;   // preview skips token-gated owner prefill
    // Pre-fill Document Owner(s) from the document's REAL owners (from Agile
    // via token-info), so the chips match the document exactly. Falls back to
    // resolving the recipient email only when the doc carries no owner refs.
    prefillDocumentOwners();
  };

  // Pre-fill Document Owner(s) from the document's actual Agile owners. These
  // carry the correct loginId/displayName (matching the document's Title Block),
  // so we avoid the old bug where resolving the responder's email mapped to the
  // wrong Agile user (e.g. "Administrator, Administrator").
  function prefillDocumentOwners() {
    var owners = (DCO.info && DCO.info.owners) || [];
    if (owners.length) {
      var added = false;
      owners.forEach(function (o) {
        if (!o || !o.loginId) return;
        var loginId = String(o.loginId);
        for (var j = 0; j < DCO.selectedUsers.documentOwners.length; j++) {
          if (DCO.selectedUsers.documentOwners[j].loginId === loginId) return;
        }
        DCO.selectedUsers.documentOwners.push({
          loginId: loginId,
          displayName: o.displayName || o.email || '',
          email: o.email || ''
        });
        added = true;
      });
      if (added) { renderChips('documentOwners'); recomputeStatus(); }
      return;
    }
    // No structured owners on the doc — fall back to the recipient-email search.
    prefillDocumentOwnerFromRecipient();
  }

  function prefillDocumentOwnerFromRecipient() {
    if (!DCO.info || !DCO.info.recipientEmail) return;
    var email = DCO.info.recipientEmail;
    var lower = email.toLowerCase();
    // Skip DL recipients — they're not real users in Agile; whoever clicks
    // the link will type their own AD username on step 4.
    if (lower.indexOf('pdl-') === 0 || lower.indexOf('ims-') === 0) return;

    fetch('/api/ims-review/token/user-search?token=' + encodeURIComponent(DCO.token)
          + '&q=' + encodeURIComponent(email) + '&limit=5', { credentials: 'omit' })
      .then(function (r) { return r.json(); })
      .then(function (body) {
        var hits = (body && body.hits) || [];
        // Prefer exact email match; fall back to first hit if Agile's casing
        // differs (e.g. user catalog has "Vikas.Jindal@sandisk.com" but the
        // token recipient is "vikas.jindal@sandisk.com").
        var match = null;
        for (var i = 0; i < hits.length; i++) {
          if (hits[i].email && hits[i].email.toLowerCase() === lower) {
            match = hits[i]; break;
          }
        }
        if (!match && hits.length > 0) match = hits[0];
        if (!match || !match.loginId) return;

        // De-dup: don't add if same loginId already picked.
        var loginId = String(match.loginId);
        for (var j = 0; j < DCO.selectedUsers.documentOwners.length; j++) {
          if (DCO.selectedUsers.documentOwners[j].loginId === loginId) return;
        }
        DCO.selectedUsers.documentOwners.push({
          loginId: loginId,
          displayName: match.displayName || email,
          email: match.email || email
        });
        // Repaint the chips + the status gate (the People section is always
        // mounted now that the form is a single page).
        renderChips('documentOwners');
        recomputeStatus();
      })
      .catch(function () { /* best-effort; user can manually pick if it fails */ });
  }

  window.dcoClose = function () {
    DCO.open = false;
    $('dcoBackdrop').classList.remove('show');
    $('dcoDrawer').classList.remove('show');
    $('dcoDrawer').setAttribute('aria-hidden', 'true');
  };

  window.dcoMaybeClose = function () {
    if (anyFieldFilled()) {
      if (!confirm('Discard your DCO form and close?')) return;
    }
    dcoClose();
  };

  function anyFieldFilled() {
    // Read the live DOM into state first so we count what's actually filled.
    captureFormState();
    var f = DCO.form;
    if (f.descriptionOfChange.trim() || f.reasonForChange.trim() || f.priority
        || f.trainingRequirement || f.businessUnit
        || f.changeImpactDisposition || cidRowsFilled(f.changeImpactRows)
        || f.notifyStakeholders.trim()) return true;
    if (DCO.selectedUsers.documentOwners.length > 1) return true;  // we pre-fill 1
    if (DCO.selectedUsers.approvers.length || DCO.selectedUsers.observers.length) return true;
    if (DCO.selectedValues.productLines.length || DCO.selectedValues.subcontractors.length) return true;
    if (DCO.files.redline || DCO.files.final
        || (DCO.files.email && DCO.files.email.length)
        || (DCO.files.other && DCO.files.other.length)) return true;
    return false;
  }

  // -------------------------------------------------------------------------
  // Metadata fetch + render dispatch
  // -------------------------------------------------------------------------
  // Pre-fill Product Line / Subcontractors from the document's current values
  // (server sends data.docPrefill = {productLines:[...], subcontractors:[...]}).
  // Only fills when the user hasn't already picked, and never overrides — it's
  // a suggestion. Product Line "N/A" maps to the N/A toggle.
  function applyDocScopePrefill(p) {
    if (!p) return;
    var pls = (p.productLines || []).filter(Boolean);
    if (pls.length && !DCO.selectedValues.productLines.length) {
      if (pls.length === 1 && String(pls[0]).toUpperCase() === 'N/A') {
        DCO.productLineMode = 'none';   // dcoSetProductLineMode('none') pins ['N/A'] on render
      } else {
        DCO.productLineMode = 'specific';
        DCO.selectedValues.productLines = pls.slice();
      }
      DCO.docPrefill.productLine = true;
    }
    var subs = (p.subcontractors || []).filter(Boolean);
    if (subs.length && !DCO.selectedValues.subcontractors.length) {
      DCO.selectedValues.subcontractors = subs.slice();
      DCO.docPrefill.subcontractor = true;
    }
  }

  // Split a ';'-joined string into a trimmed, de-blanked array.
  function splitSemi(s) {
    return String(s == null ? '' : s).split(';')
      .map(function (x) { return x.trim(); }).filter(Boolean);
  }

  // Pre-fill the §04 "SDSM IMS Document — Edit" controls from the document's
  // current Agile values (carried on DCO.info by ImsDocDetailsService). Only
  // fills when the user hasn't already selected — it's a suggestion, and the
  // controls are all editable. Scalars (VAR Type / PM Checklist) resolve
  // against the live catalog for exact casing; the multi-pick / product values
  // are ';'-joined strings we split and store into DCO.selectedValues.
  function applyImsEditPrefill(info) {
    info = info || {};
    var m = DCO.meta || {};
    if (!DCO.form.varType && info.varType)
      DCO.form.varType = matchOption(m.varType, info.varType);
    if (!DCO.form.pmChecklist && info.pmChecklist)
      DCO.form.pmChecklist = matchOption(m.pmChecklist, info.pmChecklist);
    if (!DCO.selectedValues.productGroups.length && info.productGroup)
      DCO.selectedValues.productGroups = splitSemi(info.productGroup);
    if (!DCO.selectedValues.specs.length && info.spec)
      DCO.selectedValues.specs = splitSemi(info.spec);
    if (!DCO.selectedValues.products.length && info.product)
      DCO.selectedValues.products = splitSemi(info.product);
  }

  // Small "suggested from the document" note shown under an auto-filled field.
  // prefillNoteText = inner content (for field()'s helper param, which wraps it);
  // prefillNoteDiv = fully wrapped (for custom field markup like Product Line).
  function prefillNoteText() {
    return '&#128196; Auto-filled from the document &mdash; a suggestion you can change or clear.';
  }
  function prefillNoteDiv(flag) {
    return flag ? '<div class="helper">' + prefillNoteText() + '</div>' : '';
  }

  // Resolve a desired option against a live admin-list, tolerant of case and
  // partial matches; returns the list's own value (so it matches the rendered
  // radio) or the literal target when the list is absent / has no match.
  function matchOption(list, target) {
    var t = String(target).trim().toLowerCase();
    var arr = Array.isArray(list) ? list : [];
    var exact = arr.find(function (v) { return String(v).trim().toLowerCase() === t; });
    if (exact) return exact;
    var partial = arr.find(function (v) { return String(v).trim().toLowerCase().indexOf(t) !== -1; });
    return partial || target;
  }

  function fetchMetadataThenRender() {
    // Preview uses a session-admin endpoint (no token); the real flow uses the
    // token-gated one with credentials omitted.
    var url = DCO.preview
      ? '/api/ims-review/dco-form-preview-metadata'
      : '/api/ims-review/token/dco-form-metadata?token=' + encodeURIComponent(DCO.token);
    fetch(url, { credentials: DCO.preview ? 'same-origin' : 'omit' })
      .then(function (r) {
        if (!r.ok) return r.json().then(function (j) { throw new Error(j.error || ('HTTP ' + r.status)); });
        return r.json();
      })
      .then(function (body) {
        var data = body || {};
        DCO.meta = data.lists || data;
        // Default Priority to "Standard" (deck: "Standard is defaulted"). Only when
        // the user hasn't already picked and the option list actually offers it.
        if (!DCO.form.priority && Array.isArray(DCO.meta.priority)) {
          var std = DCO.meta.priority.find(function (v) { return String(v).trim().toLowerCase() === 'standard'; });
          if (std) DCO.form.priority = std;
        }
        // OBS (obsolete) defaults — pre-fill Training Requirement = "Self study
        // email" and Change Impact Disposition = "No" (Vikas Singh 2026-06-30),
        // both editable. Resolve against the live admin-list so the value matches
        // Agile's exact casing; fall back to the literal if the list is absent.
        if (DCO.action === 'OBS') {
          if (!DCO.form.trainingRequirement)
            DCO.form.trainingRequirement = matchOption(DCO.meta.trainingRequirement, 'Self study email');
          if (!DCO.form.changeImpactDisposition)
            DCO.form.changeImpactDisposition = matchOption(DCO.meta.changeImpactDisposition, 'No');
        }
        // Suggest Product Line / Subcontractors from the document (the
        // responder can change or clear them).
        applyDocScopePrefill(data.docPrefill);
        // Pre-fill §04 SDSM IMS Document — Edit controls from the document's
        // current values (info.*). Skipped for OBS, which hides §04.
        if (DCO.action !== 'OBS') applyImsEditPrefill(DCO.info);
        renderForm();
      })
      .catch(function (e) {
        $('dcoFormContent').innerHTML =
          '<div class="dco-banner-err">Could not load form data: ' + esc(e.message) + '</div>'
        + '<p style="font-size:12px;color:var(--muted);">The plm-agile-service may be down. '
        + 'Try again in a moment, or contact pdl-plm-admin@sandisk.com.</p>';
      });
  }

  // -------------------------------------------------------------------------
  // Single-page tabular form
  // -------------------------------------------------------------------------
  // coRow — one two-column table row (label cell + value cell). `valueHtml` is
  // the control's inner markup (the same string the old field() wrappers fed
  // to their `inner` argument), so every existing control renderer + its DOM
  // IDs are reused unchanged.
  function coRow(label, required, valueHtml, infoTip, helpHtml) {
    var k = '<div class="co-k"><span>' + label + (required ? ' <span class="req">*</span>' : '')
          + (infoTip ? infoIcon(infoTip) : '') + '</span></div>';
    var v = '<div class="co-v">' + valueHtml + (helpHtml ? '<div class="co-help">' + helpHtml + '</div>' : '') + '</div>';
    return '<div class="co-row">' + k + v + '</div>';
  }
  function coSection(num, title, rowsHtml, note) {
    return '<details class="co-fsec" open><summary><span class="chev">&#9656;</span><span class="n">'
      + num + '</span>' + title + (note ? '<span class="note">' + note + '</span>' : '')
      + '</summary>' + rowsHtml + '</details>';
  }

  // Build the whole form into #dcoFormContent, then wire + prefill + gate.
  function renderForm() {
    var f = DCO.form;
    var m = DCO.meta || {};
    var docNo = (DCO.info && DCO.info.docNumber) ? DCO.info.docNumber : 'IMS doc';

    var refPanel = (typeof window.renderRefPanel === 'function')
      ? window.renderRefPanel(DCO.info || {}) : '';

    // 01 Change details
    var sec01 = coSection('01', 'Change details',
        coRow('Description of Change', true,
            '<textarea id="dco-descriptionOfChange" class="co-ta" maxlength="4000" rows="3">'
              + esc(f.descriptionOfChange) + '</textarea>',
            'Explains what is being changed in the product or process or system.')
      + coRow('Reason for Change', true,
            '<textarea id="dco-reasonForChange" class="co-ta" maxlength="4000" rows="3">'
              + esc(f.reasonForChange) + '</textarea>',
            'Explains why the change is needed.')
      + coRow('Priority', true,
            priorityControlHtml(m.priority, f.priority),
            'Agile field: Priority. Defaults to Standard.'));

    // 02 Scope
    var sec02 = coSection('02', 'Scope',
        coRow('Product Line(s)', true,
            productLineControlHtml(m.productLines || []),
            'Agile field: Product Line(s).'
              + (DCO.docPrefill.productLine
                  ? '\nAuto-filled from the document — a suggestion you can change or clear.'
                  : ''))
      + coRow('Subcontractors', true,
            subcontractorPickerHtml(m.subcontractors || []),
            'Agile field: Subcontractors.',
            DCO.docPrefill.subcontractor ? prefillNoteText() : '')
      + coRow('Training Requirement', true,
            trainingRadiosHtml(m.trainingRequirement || [], f.trainingRequirement),
            'Agile field: Training Requirement.')
      + coRow('Change Impact Disposition', true,
            changeImpactToggleHtml(m.changeImpactDisposition || [], f.changeImpactDisposition)
              + changeImpactDetailsBlock(f),
            'Agile field: Change Impact Disposition. Required by Agile\'s submit-side audit for most document styles.')
      + coRow('SDSM Business Unit', isSdsmDoc(),
            businessUnitControlHtml(m.businessUnit || [], f.businessUnit),
            isSdsmDoc()
              ? 'Required for SDSM documents (number contains -SM-). Pick one or more business units.'
              : 'Optional. Only required for SDSM documents (number contains -SM-).'));

    // 03 People
    var sec03 = coSection('03', 'People',
        coRow('Document Owner(s)', true,
            typeaheadHtml('documentOwners'), null,
            'Pre-filled from the document. Add or remove as needed.')
      + coRow('Approvers', true, typeaheadHtml('approvers'))
      + coRow('Observers', false, typeaheadHtml('observers')));

    // 04 SDSM IMS Document — Edit (editable; collected into the submit payload)
    var sec04 = coSection('04', 'SDSM IMS Document — Edit',
        imsEditHtml(DCO.info || {}),
        'Editable Agile attributes · saved with the change order');

    // Stakeholder Notification value markup — Email Copy file box + an OR
    // divider + the Email Addresses textarea, as equal-height boxes (§B10).
    // Shared by the normal and OBS §05 variants (only one is mounted at a time).
    var stakeholderValue =
        '<div class="dco-emailcopy">'
      +   mfslotHtml('Email Copy',
              'Upload the email / sign-off proving stakeholders were notified (add one or more)', 'email')
      + '</div>'
      + '<div class="dco-or-row"><div class="dco-or-line"></div>'
      +   '<span class="dco-or-pill">OR</span><div class="dco-or-line"></div></div>'
      + stakeholderControlHtml(f.notifyStakeholders);
    var stakeholderTip = 'Proof that stakeholders were told — fill **at least one** of Email Copy or Email Addresses below.';

    // 05 Attachments
    var sec05 = coSection('05', 'Attachments',
        coRow('Redline Version', true, fslotHtml('Redline Version',
            'The marked-up version showing what changed', 'redline'))
      + coRow('Final Version', true, fslotHtml('Final Version',
            'The finished document with changes accepted', 'final'))
      + coRow('Stakeholder Notification', true,
            stakeholderValue,
            stakeholderTip)
      + coRow('Other Supporting Files', false,
            mfslotHtml('Other Supporting Files',
                'Any additional files for the change order (optional; add as many as needed)', 'other')),
        'Renamed with the ' + esc(docNo) + ' number automatically');

    // OBS variant of §05: Final + Stakeholder only, no Redline (per Vikas Singh 2026-06-30)
    var sec05Obs = coSection('05', 'Attachments',
        coRow('Final Version', true, fslotHtml('Final Version',
            'Upload the document with the OBSOLETE watermark applied', 'final'))
      + coRow('Stakeholder Notification', true,
            stakeholderValue,
            stakeholderTip),
        'Renamed with the ' + esc(docNo) + ' number automatically');

    // 06 Review & sign
    var sec06 = coSection('06', 'Review & sign', signControlHtml(f));

    // OBS: hide the IMS-Document-Edit (04) section and use a slimmed Documents
    // (05) section — Final + Stakeholder only, no Redline (per Vikas Singh
    // 2026-06-30). Their required-field gating is adjusted in missingFields too.
    var isObs = DCO.action === 'OBS';
    $('dcoFormContent').innerHTML =
      refPanel + sec01 + sec02 + sec03 + (isObs ? sec05Obs : (sec04 + sec05)) + sec06 + '<div id="dcoBanner"></div>';

    wireAllControls();
    prefillForm();
    recomputeStatus();
  }

  // ---- Priority: radios when ≤4 options, <select> when more --------------
  function priorityControlHtml(options, current) {
    // Segmented control when ≤4 options; <select> when more so it doesn't wrap.
    var vals = options || [];
    if (vals.length > 0 && vals.length <= 4) {
      return '<div class="dco-radios" id="dco-priority-seg">'
        + vals.map(function (v) {
            return '<label class="dco-radio"><input type="radio" name="dco-priority-r" value="' + esc(v) + '"'
                 + (current === v ? ' checked' : '')
                 + ' onchange="dcoPickPriority(\'' + esc(v).replace(/'/g, '&#39;') + '\')"> '
                 + esc(v) + '</label>';
          }).join('')
        + '</div>'
        + '<input type="hidden" id="dco-priority" value="' + esc(current) + '">';
    }
    return selectHtml('dco-priority', vals, '-- Select --', current);
  }

  window.dcoPickPriority = function (v) {
    DCO.form.priority = v;
    var hidden = $('dco-priority');
    if (hidden) hidden.value = v;
    recomputeStatus();
  };

  // ---- Training Requirement: radios when ≤4 options, <select> otherwise ---
  // NO default — the owner must pick. Writes to the existing #dco-trainingRequirement
  // element (a hidden input in radio mode, or a <select> when there are many values).
  function trainingRadiosHtml(options, current) {
    var vals = options || [];
    if (vals.length > 0 && vals.length <= 4) {
      return '<div class="co-radios" id="dco-training-seg">'
        + vals.map(function (v) {
            return '<label class="co-radio"><input type="radio" name="dco-training-r" value="' + esc(v) + '"'
                 + (current === v ? ' checked' : '')
                 + ' onchange="dcoPickTraining(\'' + esc(v).replace(/'/g, '&#39;') + '\')"> '
                 + esc(v) + '</label>';
          }).join('')
        + '</div>'
        + '<input type="hidden" id="dco-trainingRequirement" value="' + esc(current || '') + '">';
    }
    return selectHtml('dco-trainingRequirement', vals, '-- Select --', current);
  }
  window.dcoPickTraining = function (v) {
    DCO.form.trainingRequirement = v;
    var hidden = $('dco-trainingRequirement');
    if (hidden) hidden.value = v;
    recomputeStatus();
  };

  // ---- Product Line(s): Specific/N-A radios + the multi-pick picker -------
  function productLineControlHtml(catalogVals) {
    return productLineModeRadios() + productLineChooserHtml(catalogVals);
  }

  // ---- Subcontractors: multi-pick picker ----------------------------------
  function subcontractorPickerHtml(catalogVals) {
    return pickerHtml('subcontractors', catalogVals);
  }

  // ---- Business Unit: inline multi-select toggle buttons for every doc.
  // (SDSM docs additionally require at least one — see missingFields; non-SDSM
  // is optional.) The '|'-joined hidden input feeds collectForm unchanged.
  function businessUnitControlHtml(vals, current) {
    return businessUnitButtonsHtml(vals, current);
  }

  // ---- Stakeholder Notification email-addresses textarea + AD typeahead ---
  function stakeholderControlHtml(currentVal) {
    return notifyStakeholdersHtml(currentVal);
  }

  // ---- AD username/password + attestation (was step 4 sign block) ---------
  function signControlHtml(f) {
    var lower = (DCO.info && DCO.info.recipientEmail) ? DCO.info.recipientEmail.toLowerCase() : '';
    var isDl = lower.indexOf('pdl-') === 0 || lower.indexOf('ims-') === 0;
    var actorCount = (DCO.info && DCO.info.allowedActors && DCO.info.allowedActors.length) || 1;
    var usernameLocked = !isDl && actorCount === 1 && DCO.info && DCO.info.recipientEmail;
    var multiNote = (actorCount > 1)
      ? 'This document has multiple owners &mdash; sign in with <strong>your own</strong> AD username. First response wins.'
      : '';
    return coRow('AD Username', true,
            '<input class="co-in" type="text" id="dcoUsername" value="' + esc(f.username) + '"'
              + (usernameLocked
                  ? ' readonly style="background:#FAFAF7;cursor:not-allowed;font-family:\'IBM Plex Mono\',Consolas,monospace;"'
                  : ' autocomplete="username" placeholder="your AD login ID"') + '>',
            null, multiNote)
      + coRow('AD Password', true,
            '<input class="co-in" type="password" id="dcoPassword" value="' + esc(f.password) + '" autocomplete="current-password">',
            null,
            'By signing, you confirm this IMS document was outdated and that you have the authority and information to update it. We assemble and submit the Agile change order for you &mdash; you never touch Agile directly.');
  }

  // ---- Section 04: 5 editable Agile attrs (collected into the submit payload)
  // VAR Type + PM Checklist are single-select (native <select>); Product Group
  // + Spec are large searchable multi-pick pickers; Product is a live item
  // typeahead (multi chips). All pre-filled from the document's current values
  // (DCO.form / DCO.selectedValues, seeded by applyImsEditPrefill).
  function imsEditHtml(info) {
    info = info || {};
    var m = DCO.meta || {};
    var f = DCO.form;
    return coRow('VAR Type', false,
            selectHtml('dco-varType', m.varType || [], '-- Select --', f.varType),
            'Agile field: VAR Type.')
      + coRow('Product Group', false,
            pickerHtml('productGroups', m.productGroup || []),
            'Agile field: Product Group. Type to filter; pick one or more.')
      + coRow('Spec', false,
            pickerHtml('specs', m.spec || []),
            'Agile field: Spec. Type to filter; pick one or more.')
      + coRow('Product', false,
            productTypeaheadHtml(),
            'Agile field: Product. Type 2+ chars to search Agile items by number or description.')
      + coRow('PM Checklist', false,
            selectHtml('dco-pmChecklist', m.pmChecklist || [], '-- Select --', f.pmChecklist),
            'Agile field: PM Checklist.');
  }

  // ---- Product: live item typeahead (multi-select chips) ------------------
  // Mirrors the People typeahead but hits /token/item-search and stores item
  // NUMBERS in DCO.selectedValues.products. Chip labels show "number — desc"
  // when a description is known (DCO._productDesc cache); pre-filled products
  // carry only a number, which is fine.
  function productTypeaheadHtml() {
    return '<div class="dco-typeahead">'
         + '  <input id="dco-products-input" placeholder="Type 2+ chars to search Agile items (number or description)" autocomplete="off">'
         + '  <div class="dco-typeahead-results" id="dco-products-results"></div>'
         + '  <div class="dco-chips" id="dco-products-chips" style="margin-top:6px;"></div>'
         + '</div>';
  }
  function wireProductTypeahead() {
    var input = $('dco-products-input');
    var results = $('dco-products-results');
    if (!input || !results) return;
    var debounceTimer = null;
    input.addEventListener('input', function () {
      if (debounceTimer) clearTimeout(debounceTimer);
      var q = input.value.trim();
      if (q.length < 2) { results.classList.remove('show'); return; }
      debounceTimer = setTimeout(function () { runSearch(q); }, 250);
    });
    input.addEventListener('blur', function () {
      setTimeout(function () { results.classList.remove('show'); }, 150);
    });
    function runSearch(q) {
      fetch('/api/ims-review/token/item-search?token=' + encodeURIComponent(DCO.token)
            + '&q=' + encodeURIComponent(q) + '&limit=20', { credentials: 'omit' })
        .then(function (r) { return r.json(); })
        .then(function (body) {
          var inner = body || {};
          var hits = inner.hits || (Array.isArray(inner) ? inner : []);
          renderHits(hits);
        })
        .catch(function () { renderHits([]); });
    }
    function renderHits(hits) {
      if (!hits.length) {
        results.innerHTML = '<div class="hit" style="color:var(--muted);">No matches.</div>';
      } else {
        DCO._productHits = DCO._productHits || {};
        results.innerHTML = hits.map(function (h) {
          var num = String(h.itemNumber || '');
          DCO._productHits[num] = h;
          return '<div class="hit" data-item="' + esc(num) + '">'
               + '<strong style="color:#4a6fa5;">' + esc(num) + '</strong>'
               + (h.description ? ' <span style="color:var(--muted);">' + esc(h.description) + '</span>' : '')
               + '</div>';
        }).join('');
        results.querySelectorAll('.hit').forEach(function (el) {
          el.addEventListener('mousedown', function () {
            var num = el.getAttribute('data-item');
            var hit = DCO._productHits[num];
            if (num) dcoAddProduct(num, hit ? hit.description : '');
            input.value = '';
            results.classList.remove('show');
          });
        });
      }
      results.classList.add('show');
    }
    renderProductChips();
  }
  window.dcoAddProduct = function (itemNumber, description) {
    itemNumber = String(itemNumber == null ? '' : itemNumber).trim();
    if (!itemNumber) return;
    if (DCO.selectedValues.products.indexOf(itemNumber) >= 0) return;
    DCO.selectedValues.products.push(itemNumber);
    DCO._productDesc = DCO._productDesc || {};
    if (description) DCO._productDesc[itemNumber] = description;
    renderProductChips();
    recomputeStatus();
  };
  window.dcoRemoveProduct = function (itemNumber) {
    DCO.selectedValues.products = DCO.selectedValues.products.filter(function (v) { return v !== itemNumber; });
    renderProductChips();
    recomputeStatus();
  };
  function renderProductChips() {
    var chips = $('dco-products-chips');
    if (!chips) return;
    var desc = DCO._productDesc || {};
    chips.innerHTML = DCO.selectedValues.products.map(function (num) {
      var d = desc[num];
      var label = d ? (esc(num) + ' &mdash; ' + esc(d)) : esc(num);
      return '<span class="dco-chip" data-item="' + esc(num) + '">' + label
           + '<span class="rm" onclick="dcoRemoveProduct(this.parentNode.getAttribute(\'data-item\'))">&times;</span>'
           + '</span>';
    }).join('');
  }

  // ---- Change Impact Disposition: Yes/No segmented toggle ----------------
  // Agile's field is a binary list (values "no" / "yes"). Render as a 2-button
  // toggle; picking the "yes" value reveals the Change Impact Details text
  // (which is what Agile requires when disposition = yes). Falls back to the
  // raw meta values if Agile ever returns something other than no/yes.
  function changeImpactToggleHtml(opts, current) {
    var vals = (opts && opts.length) ? opts : ['no', 'yes'];
    var c = current || '';
    var radios = vals.map(function (v) {
      var label = v.charAt(0).toUpperCase() + v.slice(1);
      return '<label class="dco-radio"><input type="radio" name="dco-cid" value="' + esc(v) + '"'
           + (c === v ? ' checked' : '')
           + ' onchange="dcoPickChangeImpact(\'' + esc(v).replace(/'/g, '&#39;') + '\')"> '
           + esc(label) + '</label>';
    }).join('');
    return '<div class="dco-radios" id="dco-cid-seg">' + radios + '</div>'
         + '<input type="hidden" id="dco-changeImpactDisposition" value="' + esc(c) + '">';
  }

  function changeImpactDetailsBlock(f) {
    var show = String(f.changeImpactDisposition || '').toLowerCase() === 'yes';
    return '<div id="dco-cid-details" style="' + (show ? '' : 'display:none;') + '">'
      + '<div class="dco-field"><span class="lbl req">Change Impact Details'
      +   infoIcon('Fill the Resources / Potential Consequences / Responsibilities table. **Resources**: People, infrastructure, environment. **Potential Consequences**: impact of the change on them. **Responsibilities**: action to take + who owns it. Required when disposition is Yes.')
      +   '</span>'
      +   '<div class="helper" style="margin-bottom:6px;">One row per impacted resource. Empty rows are dropped.</div>'
      +   '<div id="dco-cid-rows">' + cidRowsHtml(f.changeImpactRows) + '</div>'
      +   '<button type="button" class="dco-cid-add" onclick="dcoAddChangeImpactRow()">+ Add row</button>'
      + '</div>'
      + '</div>';
  }

  // ---- Change Impact Details 3-column row editor ----------------------------
  // Rows map 1:1 to the Agile HTML table (Resources / Potential Consequences /
  // Responsibilities); the agile-service renders them into the cell.
  function cidRowsHtml(rows) {
    var rs = (rows && rows.length) ? rows : [{}, {}, {}];   // default 3 blank rows
    var head = '<div class="cid-row cid-head"><div>Resources</div>'
             + '<div>Potential Consequences</div><div>Responsibilities</div><div></div></div>';
    var body = rs.map(function (r, i) {
      r = r || {};
      return '<div class="cid-row" data-cid-row="' + i + '">'
        + '<textarea class="cid-in" data-cid="resources" rows="2" oninput="dcoCidInput()">' + esc(r.resources || '') + '</textarea>'
        + '<textarea class="cid-in" data-cid="consequences" rows="2" oninput="dcoCidInput()">' + esc(r.consequences || '') + '</textarea>'
        + '<textarea class="cid-in" data-cid="responsibilities" rows="2" oninput="dcoCidInput()">' + esc(r.responsibilities || '') + '</textarea>'
        + '<button type="button" class="cid-del" title="Remove row" onclick="dcoRemoveChangeImpactRow(' + i + ')">&times;</button>'
        + '</div>';
    }).join('');
    return head + body;
  }
  function captureCidRows() {
    var container = $('dco-cid-rows');
    if (!container) return DCO.form.changeImpactRows || [];
    var rows = [];
    container.querySelectorAll('[data-cid-row]').forEach(function (rowEl) {
      var get = function (k) { var el = rowEl.querySelector('[data-cid="' + k + '"]'); return el ? el.value : ''; };
      rows.push({ resources: get('resources'), consequences: get('consequences'), responsibilities: get('responsibilities') });
    });
    return rows;
  }
  function cidRowsFilled(rows) {
    return (rows || []).some(function (r) {
      return r && ((r.resources || '').trim() || (r.consequences || '').trim() || (r.responsibilities || '').trim());
    });
  }
  window.dcoCidInput = function () {
    DCO.form.changeImpactRows = captureCidRows();
    recomputeStatus();
  };
  window.dcoAddChangeImpactRow = function () {
    DCO.form.changeImpactRows = captureCidRows();
    DCO.form.changeImpactRows.push({ resources: '', consequences: '', responsibilities: '' });
    var c = $('dco-cid-rows');
    if (c) c.innerHTML = cidRowsHtml(DCO.form.changeImpactRows);
    recomputeStatus();
  };
  window.dcoRemoveChangeImpactRow = function (i) {
    DCO.form.changeImpactRows = captureCidRows();
    if (DCO.form.changeImpactRows.length > 1) DCO.form.changeImpactRows.splice(i, 1);
    var c = $('dco-cid-rows');
    if (c) c.innerHTML = cidRowsHtml(DCO.form.changeImpactRows);
    recomputeStatus();
  };

  window.dcoPickChangeImpact = function (v) {
    DCO.form.changeImpactDisposition = v;
    var hidden = $('dco-changeImpactDisposition');
    if (hidden) hidden.value = v;
    var det = $('dco-cid-details');
    if (det) det.style.display = (String(v).toLowerCase() === 'yes') ? '' : 'none';
    recomputeStatus();
  };

  // Business Unit is "applicable to SDSM only" (green-list D8). When the doc
  // number contains '-SM-' it's MANDATORY and rendered as inline multi-select
  // toggle buttons on the label line (multi-select is allowed). For non-SDSM
  // docs it stays optional, tucked in the Advanced single-select as before.
  function isSdsmDoc() {
    var dn = (DCO.info && DCO.info.docNumber) ? String(DCO.info.docNumber).toUpperCase() : '';
    return dn.indexOf('-SM-') >= 0;
  }
  // Inline multi-select toggle buttons. The selected values are stored as a
  // '|'-joined string in a hidden input (and DCO.form.businessUnit), which the
  // agile-service splits back into a multi-list selection.
  function businessUnitButtonsHtml(vals, currentJoined) {
    var sel = {};
    String(currentJoined || '').split('|').forEach(function (v) { if (v) sel[v] = 1; });
    var btns = (vals || []).map(function (v) {
      var on = sel[v] ? ' dco-bu-on' : '';
      return '<button type="button" class="dco-bu-btn' + on + '" data-bu="' + esc(v) + '" '
           + 'onclick="dcoToggleBusinessUnit(this)">' + esc(v) + '</button>';
    }).join('');
    return '<div class="dco-bu-group" id="dco-businessUnit-group">' + btns + '</div>'
         + '<input type="hidden" id="dco-businessUnit" value="' + esc(currentJoined || '') + '">';
  }
  window.dcoToggleBusinessUnit = function (btn) {
    btn.classList.toggle('dco-bu-on');
    var on = [];
    var grp = $('dco-businessUnit-group');
    if (grp) grp.querySelectorAll('.dco-bu-btn.dco-bu-on').forEach(function (b) { on.push(b.getAttribute('data-bu')); });
    var joined = on.join('|');
    DCO.form.businessUnit = joined;
    var hidden = $('dco-businessUnit');
    if (hidden) hidden.value = joined;
    recomputeStatus();
  };

  function fslotHtml(label, hint, key) {
    var f = DCO.files[key];
    var hasFile = !!f;
    var displayHint = hasFile ? esc(f.name) + ' · ' + (f.size / 1024 / 1024).toFixed(2) + ' MB' : esc(hint);
    var btnLabel = hasFile ? 'Replace' : 'Choose file';
    return '<div class="dco-fslot' + (hasFile ? ' has-file' : '') + '" data-key="' + key + '">'
         +   '<div class="dco-fslot-meta">'
         +     '<div class="dco-fslot-title">' + (hasFile ? '<span class="check">&#10003;</span>' : '') + esc(label) + '</div>'
         +     '<div class="dco-fslot-hint">' + displayHint + '</div>'
         +   '</div>'
         +   '<input type="file" id="dco-file-' + key + '" style="display:none;">'
         +   '<button type="button" class="btn" onclick="dcoFileTrigger(\'' + key + '\')">' + btnLabel + '</button>'
         + (hasFile ? '<button type="button" class="btn" onclick="dcoRemoveFile(\'' + key + '\')" style="color:var(--error);">Remove</button>' : '')
         + '</div>';
  }

  // Multi-file slot — Email Copy + Other Supporting Files. DCO.files[key] is an
  // array; lists each picked file with a remove (×) and an "Add file(s)" button.
  function mfslotHtml(label, hint, key) {
    var arr = DCO.files[key] || [];
    var listHtml = arr.map(function (file, i) {
      return '<li>' + esc(file.name) + ' &middot; ' + (file.size / 1024 / 1024).toFixed(2) + ' MB '
           + '<a href="#" onclick="dcoRemoveMultiFile(\'' + key + '\',' + i + '); return false;" '
           + 'style="color:var(--error); text-decoration:none; font-weight:600;">&times;</a></li>';
    }).join('');
    return '<div class="dco-fslot' + (arr.length ? ' has-file' : '') + '" style="flex-wrap:wrap;" data-key="' + key + '">'
         +   '<div class="dco-fslot-meta">'
         +     '<div class="dco-fslot-title">' + (arr.length ? '<span class="check">&#10003;</span>' : '') + esc(label)
         +       (arr.length ? ' <span style="color:var(--muted); font-weight:400;">(' + arr.length + ')</span>' : '') + '</div>'
         +     (arr.length
                 ? '<ul class="dco-mf-list">' + listHtml + '</ul>'
                 : '<div class="dco-fslot-hint">' + esc(hint) + '</div>')
         +   '</div>'
         +   '<input type="file" id="dco-file-' + key + '" multiple style="display:none;">'
         +   '<button type="button" class="btn" onclick="dcoFileTrigger(\'' + key + '\')">Add file(s)</button>'
         + '</div>';
  }
  window.dcoRemoveMultiFile = function (key, idx) {
    captureFormState();   // preserve in-progress text before the rebuild
    if (Array.isArray(DCO.files[key])) DCO.files[key].splice(idx, 1);
    renderForm();
  };

  window.dcoFileTrigger = function (key) {
    var inp = $('dco-file-' + key);
    if (inp) inp.click();
  };

  // -------------------------------------------------------------------------
  // Single-page glue: wire, prefill, capture, status gate
  // -------------------------------------------------------------------------
  // Mirror every live text/select control into DCO.form. Called before any
  // in-place re-render (file slots, CID rows) so unsaved text survives, and at
  // submit time so the payload is current.
  function captureFormState() {
    var byId = function (id) { var el = $(id); return el ? el.value : ''; };
    var v;
    v = $('dco-descriptionOfChange'); if (v) DCO.form.descriptionOfChange = v.value;
    v = $('dco-reasonForChange');     if (v) DCO.form.reasonForChange     = v.value;
    v = $('dco-priority');            if (v) DCO.form.priority            = v.value;
    v = $('dco-trainingRequirement'); if (v) DCO.form.trainingRequirement = v.value;
    v = $('dco-businessUnit');        if (v) DCO.form.businessUnit        = v.value;
    v = $('dco-changeImpactDisposition'); if (v) DCO.form.changeImpactDisposition = v.value;
    v = $('dco-varType');             if (v) DCO.form.varType             = v.value;
    v = $('dco-pmChecklist');         if (v) DCO.form.pmChecklist         = v.value;
    if ($('dco-cid-rows')) DCO.form.changeImpactRows = captureCidRows();
    v = $('dco-notifyStakeholders');  if (v) DCO.form.notifyStakeholders  = v.value;
    v = $('dcoUsername');             if (v) DCO.form.username            = v.value;
    v = $('dcoPassword');             if (v) DCO.form.password            = v.value;
    return byId;   // (kept for symmetry; callers ignore)
  }

  // Bind every control's events: the same picker/typeahead/file/typeahead-AD
  // wiring the old per-step code did, PLUS a blanket recomputeStatus() on input
  // so the footer gate updates live as the user types.
  function wireAllControls() {
    document.querySelectorAll('#dcoFormContent input, #dcoFormContent select, #dcoFormContent textarea')
      .forEach(function (el) {
        el.addEventListener('input', recomputeStatus);
        el.addEventListener('change', recomputeStatus);
      });
    // People typeaheads
    wireTypeahead('documentOwners');
    wireTypeahead('approvers');
    wireTypeahead('observers');
    // Scope multi-pick pickers
    wirePicker('productLines');
    wirePicker('subcontractors');
    renderPickerChips('productLines');
    renderPickerChips('subcontractors');
    // §04 SDSM IMS Document — Edit multi-pick pickers + product typeahead
    // (no-ops when §04 isn't mounted, e.g. OBS — the wire helpers guard on
    // element existence).
    wirePicker('productGroups');
    wirePicker('specs');
    renderPickerChips('productGroups');
    renderPickerChips('specs');
    wireProductTypeahead();
    // Re-apply Product Line mode so the picker's disabled state + opacity match
    // the prior choice after any in-place re-render.
    if (typeof window.dcoSetProductLineMode === 'function') {
      window.dcoSetProductLineMode(DCO.productLineMode || 'specific');
    }
    // File slots
    bindFileSlot('redline');
    bindFileSlot('final');
    bindFileSlot('email');
    bindFileSlot('other');
    // Stakeholder Notification AD typeahead
    wireNotifyStakeholdersTypeahead();
  }

  // Re-apply the on-open prefills after the DOM is (re)built. Owner chips were
  // pushed into state by prefillDocumentOwners()/…FromRecipient() (which run in
  // dcoOpen); this paints them. Priority default + doc scope prefill are applied
  // in fetchMetadataThenRender before the first render.
  function prefillForm() {
    renderChips('documentOwners');
    renderChips('approvers');
    renderChips('observers');
  }

  // The single source of truth for "what's still missing" + the footer gate.
  // Replaces the old per-step missingFields + refreshNavButtons + stepper.
  function missingFields() {
    captureFormState();
    var f = DCO.form;
    var m = [];
    // 01 Change details
    if (!f.descriptionOfChange.trim()) m.push('description of change');
    if (!f.reasonForChange.trim()) m.push('reason for change');
    if (!f.priority) m.push('priority');
    // 02 Scope
    if (!DCO.selectedValues.productLines.length) m.push('product line');
    if (!DCO.selectedValues.subcontractors.length) m.push('subcontractor');
    if (!f.trainingRequirement) m.push('training');
    if (isSdsmDoc() && !String(f.businessUnit || '').trim()) m.push('business unit');
    if (!f.changeImpactDisposition) m.push('change impact');
    if (String(f.changeImpactDisposition).toLowerCase() === 'yes' && !cidRowsFilled(f.changeImpactRows)) m.push('change impact details');
    // 03 People
    if (!DCO.selectedUsers.documentOwners.length) m.push('document owner');
    if (!DCO.selectedUsers.approvers.length) m.push('approver');
    // 05 Documents — Redline only for non-OBS; Final + Stakeholder required for both.
    if (DCO.action !== 'OBS' && !DCO.files.redline) m.push('redline version');
    if (!DCO.files.final) m.push('final version');
    if (!(DCO.files.email && DCO.files.email.length) && !f.notifyStakeholders.trim())
        m.push('stakeholder notification (file or email addresses)');
    // 06 Review & sign
    if (!(f.username || '').trim()) m.push('AD username');
    if (!f.password) m.push('AD password');
    return m;
  }
  function formComplete() { return missingFields().length === 0; }

  // recomputeStatus — evaluate ALL required fields at once; drive the footer
  // status line + the Sign & submit button. Replaces refreshNavButtons.
  function recomputeStatus() {
    var sub  = $('dcoSubmitBtn');
    var line = $('dcoStatusLine');
    if (!sub || !line) return;

    if (DCO.preview) {
      sub.disabled = true;
      line.className = 'co-foot-status';
      line.textContent = 'Preview mode — submit disabled.';
      return;
    }
    if (DCO.submitting) {
      sub.disabled = true;
      line.className = 'co-foot-status';
      line.textContent = DCO._progress || 'Submitting…';
      return;
    }
    var missing = missingFields();
    if (missing.length === 0) {
      sub.disabled = false;
      line.className = 'co-foot-status ok';
      line.textContent = '✓ All required fields complete.';
    } else {
      sub.disabled = true;
      line.className = 'co-foot-status';
      line.textContent = 'Still needed: ' + missing.join(', ');
    }
  }

  // -------------------------------------------------------------------------
  // Field helpers (reused across control renderers)
  // -------------------------------------------------------------------------
  // tip → small ℹ️ next to the label. Click (or focus + Enter) opens a rich,
  // multi-line popover instead of the old native hover tooltip (A75: "make the
  // tooltip clickable and show more text with custom formatting / multi-line").
  // The tip lives in data-tip; supports \n line breaks and **bold**.
  function infoIcon(tip) {
    return tip ? ' <span class="dco-info" tabindex="0" role="button" aria-label="Show field info" data-tip="' + esc(tip) + '">&#9432;</span>' : '';
  }

  // --- Clickable field-info popover (shared by every .dco-info icon) --------
  function renderTip(raw) {
    // esc first, then enable simple formatting: newlines → <br>, **bold**.
    return esc(raw || '').replace(/\n/g, '<br>').replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  }
  function closeDcoTip() {
    var p = document.getElementById('dcoTipPop');
    if (p && p.parentNode) p.parentNode.removeChild(p);
  }
  function openDcoTip(icon) {
    closeDcoTip();
    var tip = icon.getAttribute('data-tip');
    if (!tip) return;
    var pop = document.createElement('div');
    pop.id = 'dcoTipPop';
    pop.className = 'dco-tip-pop';
    pop.innerHTML = renderTip(tip);
    pop.__owner = icon;
    document.body.appendChild(pop);
    var r = icon.getBoundingClientRect();
    var top = r.bottom + window.scrollY + 6;
    var left = r.left + window.scrollX;
    var maxLeft = window.scrollX + document.documentElement.clientWidth - pop.offsetWidth - 12;
    if (left > maxLeft) left = Math.max(window.scrollX + 8, maxLeft);
    pop.style.top = top + 'px';
    pop.style.left = left + 'px';
  }
  if (!window.__dcoTipInit) {
    window.__dcoTipInit = true;
    document.addEventListener('click', function (e) {
      var icon = e.target.closest ? e.target.closest('.dco-info') : null;
      if (icon) {
        e.preventDefault();
        var open = document.getElementById('dcoTipPop');
        if (open && open.__owner === icon) { closeDcoTip(); return; }
        openDcoTip(icon);
        return;
      }
      if (!e.target.closest || !e.target.closest('#dcoTipPop')) closeDcoTip();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { closeDcoTip(); return; }
      if ((e.key === 'Enter' || e.key === ' ') && e.target && e.target.classList
          && e.target.classList.contains('dco-info')) {
        e.preventDefault(); openDcoTip(e.target);
      }
    });
  }
  function selectHtml(id, vals, placeholder, current) {
    var c = current || '';
    var opts = '<option value=""' + (c === '' ? ' selected' : '') + '>' + esc(placeholder) + '</option>';
    (vals || []).forEach(function (v) {
      opts += '<option value="' + esc(v) + '"' + (c === v ? ' selected' : '') + '>' + esc(v) + '</option>';
    });
    return '<select id="' + id + '">' + opts + '</select>';
  }
  function typeaheadHtml(key) {
    return '<div class="dco-typeahead">'
         + '  <input id="dco-' + key + '-input" placeholder="Type 2+ chars to search active Agile users" autocomplete="off">'
         + '  <div class="dco-typeahead-results" id="dco-' + key + '-results"></div>'
         + '  <div class="dco-chips" id="dco-' + key + '-chips"></div>'
         + '</div>';
  }

  // -------------------------------------------------------------------------
  // Agile-style multi-pick picker (Product Lines / Subcontractors)
  // -------------------------------------------------------------------------
  // Typeahead-style multi-pick: chips above, a single input that opens a
  // results dropdown on focus/typing. Values are filtered client-side from
  // the cached admin-list catalog (DCO.meta[key]) so it's all local — no
  // round-trip per keystroke. Behaves like the people picker but with a
  // static catalog and no minimum-char threshold.
  // "Notify these people on submit" — an optional, free-form list of email
  // addresses with an AD-backed typeahead. Stakeholders to notify don't have
  // to be Agile users (many are DLs or non-engineering contacts), so we hit
  // /token/ad-search instead of the Agile user-search. Picked entries get
  // appended to the textarea as comma-separated emails so the existing
  // tokenize() call on submit still works without changes.
  function notifyStakeholdersHtml(currentVal) {
    return ''
      + '<div class="dco-notify-wrap" style="position:relative;">'
      +   '<textarea id="dco-notifyStakeholders" class="dco-notify-ta" rows="2"'
      +     ' placeholder="Email addresses — type to search the directory, or paste from Outlook (DLs OK; formatting is cleaned up).">'
      +     esc(currentVal || '')
      +   '</textarea>'
      +   '<div class="dco-typeahead-results" id="dco-notifyStakeholders-results"'
      +     ' style="position:absolute; left:0; right:0; top:100%; z-index:30;"></div>'
      + '</div>';
  }

  /** Wire the AD typeahead on the notify textarea. Last-word-after-comma rule:
   *  the typed query is the trailing token after the last comma / newline, so
   *  the user can keep adding without losing previously-entered emails. */
  function wireNotifyStakeholdersTypeahead() {
    var ta = $('dco-notifyStakeholders');
    var results = $('dco-notifyStakeholders-results');
    if (!ta || !results) return;
    var debounceTimer = null;

    function lastTokenQuery() {
      var v = ta.value || '';
      var caret = ta.selectionEnd == null ? v.length : ta.selectionEnd;
      var head = v.slice(0, caret);
      // Split on comma or newline; take the trailing fragment.
      var parts = head.split(/[,\n]/);
      return parts[parts.length - 1].trim();
    }

    function show(rows) {
      if (!rows || !rows.length) { results.classList.remove('show'); results.innerHTML = ''; return; }
      results.innerHTML = rows.map(function (u) {
        var name = esc(u.displayName || u.sAMAccountName || u.email || '');
        var mail = esc(u.email || '');
        return '<div class="hit" data-mail="' + mail + '" data-name="' + name + '">'
             + '<strong>' + name + '</strong>'
             + (mail ? ' <span style="color:var(--muted); font-size:11px;">&lt;' + mail + '&gt;</span>' : '')
             + '</div>';
      }).join('');
      results.classList.add('show');
      results.querySelectorAll('.hit').forEach(function (el) {
        el.addEventListener('mousedown', function (e) {
          e.preventDefault();
          var mail = el.getAttribute('data-mail');
          if (mail) dcoNotifyInsertPick(mail);
        });
      });
    }

    function search() {
      var q = lastTokenQuery();
      if (q.length < 2 || q.indexOf('@') >= 0) {
        // Below 2 chars OR already a complete-looking email — no typeahead.
        results.classList.remove('show');
        return;
      }
      fetch('/api/ims-review/token/ad-search?token=' + encodeURIComponent(DCO.token)
          + '&q=' + encodeURIComponent(q))
        .then(function (r) { return r.json(); })
        .then(function (j) { show((j && j.results) || []); })
        .catch(function () { results.classList.remove('show'); });
    }

    ta.addEventListener('input', function () {
      if (debounceTimer) clearTimeout(debounceTimer);
      debounceTimer = setTimeout(search, 200);
      recomputeStatus();
    });
    ta.addEventListener('blur', function () {
      setTimeout(function () { results.classList.remove('show'); }, 150);
    });
  }

  /** Replace the trailing typed token with the picked email + a comma + space
   *  so the user can keep typing the next one. */
  window.dcoNotifyInsertPick = function (email) {
    var ta = $('dco-notifyStakeholders');
    if (!ta) return;
    var v = ta.value || '';
    var caret = ta.selectionEnd == null ? v.length : ta.selectionEnd;
    var head = v.slice(0, caret);
    var tail = v.slice(caret);
    // Find the last comma/newline before the caret; replace everything after it.
    var lastSep = Math.max(head.lastIndexOf(','), head.lastIndexOf('\n'));
    var before = lastSep >= 0 ? head.slice(0, lastSep + 1) : '';
    // Only glue a separator when there is already committed content before the
    // caret (i.e. `before` is non-empty and ends in a delimiter). The FIRST
    // address must start clean — no leading comma. When `before` is empty
    // (nothing committed yet; the trailing typed query token is what's being
    // replaced) the prefix stays empty. Fixes the leading-comma bug (§B11).
    var prefix = before + (before && !/[ \n]$/.test(before) ? ' ' : '');
    ta.value = prefix + email + ', ' + tail.replace(/^[ ,]*/, '');
    var newCaret = (prefix + email + ', ').length;
    ta.setSelectionRange(newCaret, newCaret);
    ta.focus();
    var results = $('dco-notifyStakeholders-results');
    if (results) { results.innerHTML = ''; results.classList.remove('show'); }
    DCO.form.notifyStakeholders = ta.value;
    recomputeStatus();
  };

  // Special-case wrapper for Product Line(s): an end user often wants to say
  // "this change doesn't touch any specific product line" (e.g. a doc-control
  // doc that's company-wide). Show two radio choices above the picker — when
  // "No product lines touched" is selected, the picker is greyed out and the
  // server gets the single Agile value "N/A" (which IS in the catalog as a
  // selectable option, so no validator edge cases). Choosing "Yes" restores
  // the picker for normal multi-select.
  // Yes/N-A radios — rendered inline next to the "Product Line(s)" label.
  function productLineModeRadios() {
    var isNone = (DCO.productLineMode || 'specific') === 'none';
    return '<div class="dco-radios" id="dco-pl-seg">'
      +   '<label class="dco-radio"><input type="radio" name="dco-pl-mode" value="specific"' + (isNone ? '' : ' checked') + ' onchange="dcoSetProductLineMode(\'specific\')"> Yes</label>'
      +   '<label class="dco-radio"><input type="radio" name="dco-pl-mode" value="none"' + (isNone ? ' checked' : '') + ' onchange="dcoSetProductLineMode(\'none\')"> N/A</label>'
      + '</div>';
  }
  // Picker box — sits on its own line below the label+radios; dimmed when N/A.
  function productLineChooserHtml(catalogVals) {
    var isNone = (DCO.productLineMode || 'specific') === 'none';
    return '<div id="dco-pl-picker-wrap" style="margin-top:6px;' + (isNone ? ' opacity:0.45; pointer-events:none;' : '') + '">'
      +   pickerHtml('productLines', catalogVals)
      + '</div>';
  }

  /** Toggle between "pick specific product lines" and "no product line touched".
   *  Selecting 'none' clears any chips and sets the value to ['N/A']. Selecting
   *  'specific' clears the N/A pin so the user can pick from the catalog again. */
  window.dcoSetProductLineMode = function (mode) {
    DCO.productLineMode = (mode === 'none') ? 'none' : 'specific';
    if (DCO.productLineMode === 'none') {
      DCO.selectedValues.productLines = ['N/A'];
    } else if (DCO.selectedValues.productLines.length === 1
            && DCO.selectedValues.productLines[0] === 'N/A') {
      DCO.selectedValues.productLines = [];
    }
    var wrap = $('dco-pl-picker-wrap');
    if (wrap) {
      wrap.style.opacity = (DCO.productLineMode === 'none') ? '0.45' : '';
      wrap.style.pointerEvents = (DCO.productLineMode === 'none') ? 'none' : '';
    }
    // Also flip the input's disabled attribute so keyboard Tab can't land
    // on it while "no product line" is selected (pointer-events:none only
    // blocks mouse).
    var input = $('dco-pick-productLines-input');
    if (input) input.disabled = (DCO.productLineMode === 'none');
    // Reflect the choice on the Yes/N-A radios (when called programmatically).
    var seg = $('dco-pl-seg');
    if (seg) {
      var want = (DCO.productLineMode === 'none') ? 'none' : 'specific';
      var r = seg.querySelector('input[value="' + want + '"]');
      if (r) r.checked = true;
    }
    renderPickerChips('productLines');
    recomputeStatus();
  };

  function pickerHtml(key, vals) {
    if (!vals.length) {
      return '<div class="dco-picker"><div class="dco-picker-empty">No values available — check application.properties list name + restart agile-service.</div></div>';
    }
    // Stash the catalog on the DCO object so the dropdown render can read it.
    DCO._catalog = DCO._catalog || {};
    DCO._catalog[key] = vals;
    // Example code in the placeholder matches the field's value style
    // (subcontractors are "C002 - …", product lines are numeric like "1032").
    var eg = (key === 'subcontractors') ? 'C002' : '1032';
    return '<div class="dco-typeahead">'
         + '  <input id="dco-pick-' + key + '-input" placeholder="Type to filter (e.g. ' + eg + '), or click to browse" autocomplete="off">'
         + '  <div class="dco-typeahead-results" id="dco-pick-' + key + '-results"></div>'
         + '  <div class="dco-chips" id="dco-pick-' + key + '-chips" style="margin-top:6px;"></div>'
         + '</div>';
  }

  function wirePicker(key) {
    var input = $('dco-pick-' + key + '-input');
    var results = $('dco-pick-' + key + '-results');
    if (!input || !results) return;

    function show(q) {
      var lower = (q || '').toLowerCase();
      var catalog = (DCO._catalog && DCO._catalog[key]) || [];
      var picked = DCO.selectedValues[key] || [];
      var matches = catalog.filter(function (v) {
        if (picked.indexOf(v) >= 0) return false;             // already selected
        return lower === '' || v.toLowerCase().indexOf(lower) >= 0;
      }).slice(0, 50);                                         // cap dropdown size
      if (!matches.length) {
        results.innerHTML = '<div class="hit" style="color:var(--muted);">'
          + (catalog.length === picked.length ? 'All values selected.' : 'No matches.')
          + '</div>';
      } else {
        results.innerHTML = matches.map(function (v) {
          return '<div class="hit" data-val="' + esc(v) + '">' + esc(v) + '</div>';
        }).join('');
        results.querySelectorAll('.hit').forEach(function (el) {
          el.addEventListener('mousedown', function () {       // mousedown fires before blur
            var v = el.getAttribute('data-val');
            if (v) dcoPickerToggle(key, v);
            input.value = '';
            show('');                                          // refresh the dropdown to drop the just-picked item
            input.focus();
          });
        });
      }
      results.classList.add('show');
    }

    input.addEventListener('focus', function () { show(input.value); });
    input.addEventListener('input', function () { show(input.value); });
    input.addEventListener('blur', function () {
      setTimeout(function () { results.classList.remove('show'); }, 150);
    });
  }

  window.dcoPickerToggle = function (key, val) {
    var arr = DCO.selectedValues[key];
    var idx = arr.indexOf(val);
    if (idx >= 0) arr.splice(idx, 1);
    else arr.push(val);
    renderPickerChips(key);
    recomputeStatus();
  };
  window.dcoPickerRemove = function (key, val) {
    DCO.selectedValues[key] = DCO.selectedValues[key].filter(function (v) { return v !== val; });
    renderPickerChips(key);
    recomputeStatus();
  };
  function renderPickerChips(key) {
    var chips = $('dco-pick-' + key + '-chips');
    if (!chips) return;
    chips.innerHTML = DCO.selectedValues[key].map(function (v) {
      return '<span class="dco-chip" data-val="' + esc(v) + '">' + esc(v)
           + '<span class="rm" onclick="dcoPickerRemove(\'' + key + '\', this.parentNode.getAttribute(\'data-val\'))">&times;</span>'
           + '</span>';
    }).join('');
  }

  // -------------------------------------------------------------------------
  // Typeahead
  // -------------------------------------------------------------------------
  function wireTypeahead(key) {
    var input = $('dco-' + key + '-input');
    var results = $('dco-' + key + '-results');
    if (!input || !results) return;
    var debounceTimer = null;
    input.addEventListener('input', function () {
      if (debounceTimer) clearTimeout(debounceTimer);
      var q = input.value.trim();
      if (q.length < 2) { results.classList.remove('show'); return; }
      debounceTimer = setTimeout(function () { runSearch(q); }, 200);
    });
    input.addEventListener('blur', function () {
      setTimeout(function () { results.classList.remove('show'); }, 150);
    });
    function runSearch(q) {
      var cached = DCO.userCache[q];
      if (cached && (Date.now() - cached.at) < 60000) { renderHits(cached.hits); return; }
      fetch('/api/ims-review/token/user-search?token=' + encodeURIComponent(DCO.token)
            + '&q=' + encodeURIComponent(q) + '&limit=20', { credentials: 'omit' })
        .then(function (r) { return r.json(); })
        .then(function (body) {
          var inner = body || {};
          var hits = inner.hits || (Array.isArray(inner) ? inner : []);
          DCO.userCache[q] = { hits: hits, at: Date.now() };
          renderHits(hits);
        })
        .catch(function () { renderHits([]); });
    }
    function renderHits(hits) {
      if (!hits.length) {
        results.innerHTML = '<div class="hit" style="color:var(--muted);">No matches.</div>';
      } else {
        DCO._currentHits = DCO._currentHits || {};
        results.innerHTML = hits.map(function (h) {
          var hk = key + ':' + h.loginId;
          DCO._currentHits[hk] = h;
          return '<div class="hit" data-hitkey="' + esc(hk) + '">'
               + '<strong style="color:#4a6fa5;">' + esc(h.displayName) + '</strong>'
               + ' <span style="color:var(--muted);">&lt;' + esc(h.email) + '&gt;</span>'
               + '</div>';
        }).join('');
        results.querySelectorAll('.hit').forEach(function (el) {
          el.addEventListener('mousedown', function () {
            var hk = el.getAttribute('data-hitkey');
            var hit = DCO._currentHits[hk];
            if (hit) addChip(key, hit);
            input.value = '';
            results.classList.remove('show');
          });
        });
      }
      results.classList.add('show');
    }
    // Render any pre-existing chips for this picker (e.g. pre-filled DO)
    renderChips(key);
  }
  function addChip(key, hit) {
    if (!hit || !hit.loginId) return;
    var loginId = String(hit.loginId);
    for (var i = 0; i < DCO.selectedUsers[key].length; i++) {
      if (DCO.selectedUsers[key][i].loginId === loginId) return;
    }
    DCO.selectedUsers[key].push({
      loginId: loginId,
      displayName: hit.displayName || loginId,
      email: hit.email || ''
    });
    renderChips(key);
    recomputeStatus();
  }
  function renderChips(key) {
    var chips = $('dco-' + key + '-chips');
    if (!chips) return;
    chips.innerHTML = DCO.selectedUsers[key].map(function (u) {
      var label = u.displayName || u.loginId;
      var subline = u.email ? (' <span style="color:#6B7280;">&lt;' + esc(u.email) + '&gt;</span>') : '';
      return '<span class="dco-chip" title="loginId: ' + esc(u.loginId) + '">'
           + esc(label) + subline
           + '<span class="rm" onclick="dcoRemoveChip(\'' + esc(key) + '\', \'' + esc(u.loginId) + '\')">&times;</span>'
           + '</span>';
    }).join('');
  }
  window.dcoRemoveChip = function (key, loginId) {
    DCO.selectedUsers[key] = DCO.selectedUsers[key].filter(function (u) { return u.loginId !== loginId; });
    renderChips(key);
    recomputeStatus();
  };

  // -------------------------------------------------------------------------
  // File slots
  // -------------------------------------------------------------------------
  function bindFileSlot(key) {
    var inp = $('dco-file-' + key);
    if (!inp) return;
    inp.addEventListener('change', function () {
      var picked = inp.files;
      captureFormState();   // preserve in-progress text before the rebuild
      if (Array.isArray(DCO.files[key])) {
        for (var i = 0; i < picked.length; i++) DCO.files[key].push(picked[i]);
      } else {
        DCO.files[key] = (picked && picked.length > 0) ? picked[0] : null;
      }
      renderForm();
    });
  }
  window.dcoRemoveFile = function (key) {
    captureFormState();   // preserve in-progress text before the rebuild
    DCO.files[key] = null;
    var inp = $('dco-file-' + key);
    if (inp) inp.value = '';
    renderForm();
  };

  // -------------------------------------------------------------------------
  // Submit (final step)
  // -------------------------------------------------------------------------
  window.dcoSubmit = function () {
    if (DCO.preview) return;   // preview mode never submits
    if (DCO.submitting) return;
    captureFormState();        // pull the live DOM into DCO.form
    if (!formComplete()) { recomputeStatus(); return; }

    DCO.submitting = true;
    DCO._progress = 'Validating…';
    recomputeStatus();

    var form = collectForm();

    fetch('/api/ims-review/token/validate-dco-form', {
      method: 'POST', credentials: 'omit',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: DCO.token, form: form })
    })
      .then(function (r) { return r.json(); })
      .then(function (vbody) {
        if (!vbody.ok) {
          surfaceErrors(vbody);
          throw new Error('validate-failed');
        }
        cycleProgress(['Creating DCO…', 'Attaching files…', 'Notifying stakeholders…']);

        var fd = new FormData();
        fd.append('token', DCO.token);
        fd.append('username', DCO.form.username.trim());
        fd.append('password', DCO.form.password);
        fd.append('form', JSON.stringify(form));
        if (DCO.action !== 'OBS' && DCO.files.redline) fd.append('file_redline', DCO.files.redline);
        if (DCO.files.final)   fd.append('file_final',   DCO.files.final);
        // Email Copy → "Email Notification" attach type; Other → its own type. Multi.
        (DCO.files.email || []).forEach(function (file) { fd.append('file_email', file); });
        (DCO.files.other || []).forEach(function (file) { fd.append('file_other', file); });

        return fetch('/api/ims-review/token/submit-dco', { method: 'POST', credentials: 'omit', body: fd });
      })
      .then(function (r) {
        if (!r) return null;
        return r.json().then(function (body) { return { status: r.status, body: body }; });
      })
      .then(function (resp) {
        if (!resp) return;
        stopProgressCycle();
        if (resp.body.success) {
          dcoClose();
          document.getElementById('formCard').style.display = 'none';
          document.getElementById('successCard').style.display = '';
          var dcoNumber = resp.body.dcoNumber || '(unknown)';
          // Make the DCO# a clickable Agile link on the confirmation page: the
          // submit-dco response carries the webclient base URL so dcoAgileChangeLink
          // has a base even though DCO.meta (the form-lists payload) may not.
          if (resp.body.agileWebclientUrl) {
            DCO.meta = DCO.meta || {};
            if (!DCO.meta.agileWebclientUrl) DCO.meta.agileWebclientUrl = resp.body.agileWebclientUrl;
          }
          var attachedCount = (resp.body.attachmentsCount != null)
            ? resp.body.attachmentsCount
            : resp.body.attachmentsAttached;
          var submitted = resp.body.submitted !== false; // default to true when field absent (old server)
          var successMsgEl = document.getElementById('successMsg');
          if (submitted) {
            var msg = 'DCO ' + dcoAgileChangeLink(dcoNumber) + ' created and submitted.';
            if (attachedCount != null) msg += ' ' + esc(String(attachedCount)) + ' file(s) attached.';
            if (resp.body.stakeholdersNotified != null) msg += ' ' + esc(String(resp.body.stakeholdersNotified)) + ' stakeholder(s) notified.';
            successMsgEl.innerHTML = msg;
          } else {
            // DCO was created but auto-submit was blocked. Don't say
            // "submitted" — the DO has been emailed with instructions to
            // submit manually. Don't say "stakeholders notified" either —
            // the [New DCO] email is suppressed until the DCO actually
            // submits.
            successMsgEl.innerHTML = '';
            var line1 = document.createElement('div');
            line1.innerHTML = 'DCO ' + dcoAgileChangeLink(dcoNumber) + ' was created'
              + (attachedCount != null ? ' with ' + esc(String(attachedCount)) + ' file(s) attached' : '')
              + ', but the auto-submit step did not complete.';
            successMsgEl.appendChild(line1);
            var line2 = document.createElement('div');
            line2.style.marginTop = '8px';
            line2.style.color = '#856404';
            var reason = resp.body.submitBlockedReason;
            line2.innerHTML = (reason === 'DRR_BLOCKED')
              ? '<strong>PLM IT is tracking the deferred submission</strong> &mdash; check your inbox for the follow-up note. You do not need to take action.'
              : '<strong>Please check your inbox</strong> &mdash; we just sent you instructions to open the DCO in Agile, fill the missing field named in the audit message, and click <em>Next Status &rarr; Submit</em>.';
            successMsgEl.appendChild(line2);
          }
          if (resp.body.signedBy) {
            document.getElementById('successPdf').innerHTML =
              'Signed by <strong>' + esc(resp.body.signedBy) + '</strong>'
              + (resp.body.signedEmail ? ' &lt;' + esc(resp.body.signedEmail) + '&gt;' : '') + '.';
          }
          // Footer line: the default "Doc Control has been notified" is only
          // true when the DCO actually submitted (that's when the [New DCO]
          // stakeholder email fires). When submit was blocked, the stakeholder
          // notify is suppressed, so reword to match what really happened.
          var footerEl = document.getElementById('successFooter');
          if (footerEl) {
            footerEl.textContent = submitted
              ? 'Doc Control has been notified. You can close this tab.'
              : 'You can close this tab once you have read the follow-up email.';
          }
        } else {
          if (resp.body.fieldErrors) surfaceErrors({ fieldErrors: resp.body.fieldErrors });
          var b = document.createElement('div');
          b.className = 'dco-banner-err';
          var msg = esc(resp.body.error || 'Submit failed');
          if (resp.body.corrId) {
            msg += '<br><span style="font-size:11px;font-family:\'IBM Plex Mono\',Consolas,monospace;">Reference: corrId=' + esc(resp.body.corrId) + '</span>';
          }
          b.innerHTML = msg;
          var ban = $('dcoBanner');
          if (ban) { ban.innerHTML = ''; ban.appendChild(b); }
        }
      })
      .catch(function (e) {
        if (e && e.message !== 'validate-failed') {
          stopProgressCycle();
          var b = document.createElement('div');
          b.className = 'dco-banner-err';
          b.textContent = 'Submit failed: ' + e.message;
          var ban = $('dcoBanner');
          if (ban) { ban.innerHTML = ''; ban.appendChild(b); }
        }
      })
      .then(function () {
        DCO.submitting = false;
        DCO._progress = '';
        recomputeStatus();
      });
  };

  function collectForm() {
    // SAME shape as before — backend contract unchanged.
    var f = DCO.form;
    return {
      action:                    DCO.action,   // 'OBS' drives the rev->"OB" override server-side
      priority:                  f.priority,
      descriptionOfChange:       f.descriptionOfChange.trim(),
      reasonForChange:           f.reasonForChange.trim(),
      productLines:              DCO.selectedValues.productLines.slice(),
      subcontractors:            DCO.selectedValues.subcontractors.slice(),
      trainingRequirement:       f.trainingRequirement,
      businessUnit:              f.businessUnit,
      changeImpactDisposition:   f.changeImpactDisposition,
      changeImpactRows:          (f.changeImpactRows || []).map(function (r) {
        return { resources: (r.resources || '').trim(), consequences: (r.consequences || '').trim(),
                 responsibilities: (r.responsibilities || '').trim() };
      }).filter(function (r) { return r.resources || r.consequences || r.responsibilities; }),
      documentOwners:            DCO.selectedUsers.documentOwners.slice(),
      approvers:                 DCO.selectedUsers.approvers.slice(),
      observers:                 DCO.selectedUsers.observers.slice(),
      // §04 SDSM IMS Document — Edit (editable Agile attributes).
      varType:                   f.varType || '',
      pmChecklist:               f.pmChecklist || '',
      productGroups:             DCO.selectedValues.productGroups.slice(),
      specs:                     DCO.selectedValues.specs.slice(),
      products:                  DCO.selectedValues.products.slice(),
      notifyStakeholders:        extractEmails(f.notifyStakeholders),
      attachmentManifest:        buildManifest()
    };
  }
  function tokenize(s) {
    if (!s) return [];
    return s.split(/[,\n;]/).map(function (x) { return x.trim(); }).filter(Boolean);
  }
  // Pull clean email addresses out of free-form text. Handles Outlook
  // copy-paste formats — "Last, First <addr>", "Name <addr>",
  // semicolon/comma/newline separated, and bare addresses. Because we
  // extract by the @-pattern (not by delimiter), commas inside an Outlook
  // display name ("Kolam, Bibi Anita <addr>") no longer split an address
  // apart. Lower-cased + de-duped.
  function extractEmails(s) {
    if (!s) return [];
    var out = [], seen = {};
    var re = /[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/g, m;
    while ((m = re.exec(s)) !== null) {
      var e = m[0].toLowerCase();
      if (!seen[e]) { seen[e] = true; out.push(e); }
    }
    return out;
  }
  function buildManifest() {
    var out = [];
    if (DCO.files.redline) out.push({ type: 'Redline', filename: DCO.files.redline.name, sizeBytes: DCO.files.redline.size });
    if (DCO.files.final)   out.push({ type: 'Final',   filename: DCO.files.final.name,   sizeBytes: DCO.files.final.size });
    (DCO.files.email || []).forEach(function (file) { out.push({ type: 'Email', filename: file.name, sizeBytes: file.size }); });
    (DCO.files.other || []).forEach(function (file) { out.push({ type: 'Other', filename: file.name, sizeBytes: file.size }); });
    return out;
  }

  function surfaceErrors(body) {
    if (body.fieldErrors && Object.keys(body.fieldErrors).length) {
      var msgs = Object.keys(body.fieldErrors).map(function (k) {
        return esc(k + ': ' + body.fieldErrors[k]);
      }).join('<br>');
      var ban = $('dcoBanner');
      if (ban) ban.innerHTML = '<div class="dco-banner-err">' + msgs + '</div>';
    } else if (body.formErrors && body.formErrors.length) {
      var ban2 = $('dcoBanner');
      if (ban2) ban2.innerHTML = '<div class="dco-banner-err">' + body.formErrors.map(esc).join('<br>') + '</div>';
    }
  }

  var _progressTimer = null;
  function cycleProgress(messages) {
    var i = 0;
    DCO._progress = messages[0];
    recomputeStatus();
    if (_progressTimer) clearInterval(_progressTimer);
    _progressTimer = setInterval(function () {
      i = (i + 1) % messages.length;
      DCO._progress = messages[i];
      recomputeStatus();
    }, 2500);
  }
  function stopProgressCycle() {
    if (_progressTimer) { clearInterval(_progressTimer); _progressTimer = null; }
  }
})();
