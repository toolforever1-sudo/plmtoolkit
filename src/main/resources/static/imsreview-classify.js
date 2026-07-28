/* Pure tile-classification for the IMS Dashboard. No window/document refs so
 * it can be unit-tested under `node --test`. Loaded in the browser before
 * imsreview.js (exposes window.ImsClassify); required directly in tests. */
(function (root, factory) {
    var api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (typeof window !== 'undefined') window.ImsClassify = api;
})(this, function () {
    'use strict';

    function hasDrrOf(r) {
        if (typeof r.hasDrr === 'boolean') return r.hasDrr;
        return !!(r.drrNumber && r.drrNumber.trim && r.drrNumber.trim().length > 0);
    }

    // Mirror the server-side owner-health semantics for payloads that lack
    // the precomputed flags (older JAR mid-deploy). "Valid" = ACTIVE/UNKNOWN/null.
    function allOwnersLeftOf(r) {
        if (typeof r.allOwnersLeft === 'boolean') return r.allOwnersLeft;
        var owners = r.owners || [];
        var hasMissing = false, hasDisabled = false, hasValid = false;
        for (var i = 0; i < owners.length; i++) {
            var s = owners[i].ldapStatus;
            if (s === 'NOT_FOUND') hasMissing = true;
            else if (s === 'DISABLED') hasDisabled = true;
            else hasValid = true;
        }
        return owners.length > 0 && !hasValid && hasMissing && !hasDisabled;
    }

    // Map a legacy DRR's Agile workflow status name into the two buckets the
    // deck distinguishes. The in-scope statuses (statustype 0,1,2) are exactly
    // Pending / Submit / CCB, confirmed against live agile_prod — so this is an
    // exact map, not a guess. Anything else (incl. empty) falls to "pending".
    function imsDrrStatusBucket(name) {
        var s = (name || '').trim().toLowerCase();
        if (s === 'submit' || s === 'ccb') return 'submit_ccb';
        return 'pending';
    }

    // A DRR/DCO Agile workflow status that means the review is DONE (closed).
    // The Agile change ladder is Pending -> Submit -> CCB -> Release -> Implemented
    // -> Cancel. Only IMPLEMENTED (statustype 4) means the change is incorporated
    // and the review closed; the relationship rule flips the DRR to Implemented
    // when the DCO implements. "Release" (statustype 3) is NOT closed — the change
    // is released but not yet incorporated, so the review is still in process.
    // The toolkit's own queue status never advances past DO_NEEDS_CHANGE once a
    // DCO is created (the post-submit Agile workflow happens outside the toolkit),
    // so we read the terminal state off the live DRR/DCO status instead.
    function isTerminalAgileStatus(name) {
        var s = (name || '').trim().toLowerCase();
        return s === 'implemented' || s === 'complete' || s === 'completed';
    }

    // True when the document's DRR or DCO has reached a terminal Agile status.
    // Backend may also precompute r.agileClosed; honor it when present.
    function imsAgileClosed(r) {
        if (typeof r.agileClosed === 'boolean') return r.agileClosed;
        return isTerminalAgileStatus(r.drrStatus) || isTerminalAgileStatus(r.dcoStatus);
    }

    // True when the DCO has been RELEASED (statustype 3) but not yet Implemented,
    // and the review isn't otherwise closed — i.e. "in process, awaiting
    // implementation". Surfaced prominently so the reviewer knows the change
    // cleared CCB and just needs to be incorporated.
    function imsDcoReleasedPending(r) {
        var s = ((r && r.dcoStatus) || '').trim().toLowerCase();
        return (s === 'release' || s === 'released') && !imsAgileClosed(r);
    }

    // `anchor` param retained for call-site compatibility but no longer used:
    // New-vs-Legacy is now decided by whether the TOOLKIT has handled the doc
    // (queue activity), not by a DRR creation date. "New DRR" = the doc was
    // sent through the dashboard (status advanced past NOT_SENT) or the toolkit
    // created a DCO for it. A pre-existing DRR the toolkit hasn't touched is
    // "Legacy". This is the definitive "created/handled from the toolkit"
    // signal (per Vikas 2026-06-29) — the date proxy mis-classified docs whose
    // create-date was unavailable.
    function imsClassifyTile(r, anchor) {
        if (!hasDrrOf(r)) {
            return r.drrExempt
                ? { group: 'need_drr', tile: 'no_drr_required' }
                : { group: 'need_drr', tile: 'drr_missing' };
        }
        var allLeft = allOwnersLeftOf(r);
        var toolkit = (r.status && r.status !== 'NOT_SENT') || !!r.agileDco;
        if (toolkit) {
            // New DRR — driven by our own queue status.
            // Terminal Agile state wins over everything: once the DRR/DCO is
            // Implemented the review is done, even if the queue status is still
            // DO_NEEDS_CHANGE and even if the owners have since left.
            if (imsAgileClosed(r)) return { group: 'new', tile: 'closed' };
            // NEED OWNER overrides the remaining (in-flight) states.
            if (allLeft) return { group: 'new', tile: 'need_owner' };
            switch (r.status) {
                case 'DO_NEED_HELP': return { group: 'new', tile: 'need_help' };
                case 'DM_APPROVED':
                case 'CANCELLED':    return { group: 'new', tile: 'closed' };
                case 'SENT_TO_DO':   return { group: 'new', tile: 'pending_response' };
                case 'SENT_TO_DM':
                case 'DO_NEEDS_CHANGE':
                default:             return { group: 'new', tile: 'in_process' }; // incl. NOT_SENT-with-DCO edge
            }
        }
        // Legacy DRR — pre-existing, not handled by the toolkit; sub-state from
        // the DRR's live Agile workflow status.
        if (allLeft) return { group: 'legacy', tile: 'need_owner' };
        return { group: 'legacy', tile: imsDrrStatusBucket(r.drrStatus) === 'submit_ccb' ? 'in_process' : 'pending_response' };
    }

    function imsTileKey(r, anchor) {
        var c = imsClassifyTile(r, anchor);
        return c.group + '.' + c.tile;
    }

    function imsTileCounts(rows, anchor) {
        var counts = {};
        (rows || []).forEach(function (r) {
            var k = imsTileKey(r, anchor);
            counts[k] = (counts[k] || 0) + 1;
        });
        return counts;
    }

    return {
        imsDrrStatusBucket: imsDrrStatusBucket,
        imsAgileClosed: imsAgileClosed,
        imsDcoReleasedPending: imsDcoReleasedPending,
        imsClassifyTile: imsClassifyTile,
        imsTileKey: imsTileKey,
        imsTileCounts: imsTileCounts
    };
});
