// ========================================================================
// ECN Report Tab — ecnreport.js
// ========================================================================

// General ECNs (formerly "Standard ECN") classification set. Used for filtering
// and labelling; targets themselves come from the SLA matrix (priority-based,
// same as every other classification — see PT-88, 2026-06-12).
// Keep this list in sync with STANDARD_ECN_CLASSIFICATIONS in ecn_report_generator.py.
var STANDARD_ECN_CLASSIFICATIONS = ['Standard ECNs', 'Standard ECN', 'General ECNs', 'General ECN'];

function ecnIsStandardEcn(row) {
    return STANDARD_ECN_CLASSIFICATIONS.indexOf(row.ecnClassification || '') !== -1;
}

// ── State ────────────────────────────────────────────────────────────
var ecnState = {
    ecns: [],
    baselineTargets: {},
    currentTargets: {},
    activeProfile: null,
    profiles: [],
    annotations: {},
    canEdit: false,
    isAdmin: false,
    generatedAt: null,
    dateRange: null,
    loaded: false,
    tableFilter: '',
    columnFilters: {},  // { columnKey: 'value' } — empty/'(All)' means no filter
    deltaFilter: null,  // null = all, or { min, max, label } — filter by Δ Days range
    showOnlyCompleted: true,  // default: hide in-progress ECNs (no Actual Days yet)
    expandedCharts: {},  // { canvasId: true } — user manually expanded a low-variance chart
    expandedQuarterly: {},  // { canvasId: true } — PT-74: per-panel quarterly trend toggled open
    quarterlyTrends: null,  // PT-74: stashed by ecnComputeStats so chart fns can read it
    teamMonthly: null,      // PT-77: top-6-teams + Others, count per (team, month)
    teamQuarterly: null,    // PT-77: same shape on last-3-completed quarters
    tablePage: 0,
    tablePageSize: 50,
    tableSort: { col: null, asc: true },
    dirtyTargets: {},
    appliedDirtyCount: 0,
    chartInstances: {}
};

// ── Tab Entry Point ──────────────────────────────────────────────────

function ecnLoadTab() {
    if (ecnState.loaded) {
        ecnRefreshUI();
        return;
    }
    ecnFetchData();
}

function ecnFetchData() {
    document.getElementById('ecnReportStatus').textContent = 'Loading data...';
    fetch('/api/ecn-report/data')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data.success) {
                document.getElementById('ecnReportStatus').textContent = data.message || 'No data available.';
                document.getElementById('ecnDashboard').style.display = 'none';
                return;
            }
            var ecnData = data.ecnData;
            ecnState.ecns = ecnData.ecns || [];
            ecnState.baselineTargets = ecnData.baselineTargets || {};
            ecnState.generatedAt = ecnData.generatedAt;
            ecnState.dateRange = ecnData.dateRange;
            ecnState.activeProfile = data.activeProfile;
            ecnState.annotations = data.annotations || {};
            ecnState.canEdit = data.canEdit;
            ecnState.isAdmin = data.isAdmin;
            ecnState.loaded = true;

            ecnState.currentTargets = ecnMergeTargets(ecnState.baselineTargets, ecnState.activeProfile);
            ecnState.dirtyTargets = {};

            ecnFetchProfiles();

            if (ecnState.canEdit) {
                document.getElementById('ecnEmailBtn').style.display = '';
                document.getElementById('ecnUploadSlaBtn').style.display = '';
                document.getElementById('ecnPromoteBtn').style.display = '';
            }
            if (ecnState.isAdmin) {
                document.getElementById('ecnManageEditorsBtn').style.display = '';
            }

            ecnRefreshUI();
        })
        .catch(function(err) {
            document.getElementById('ecnReportStatus').textContent = 'Error loading data: ' + err.message;
        });
}

function ecnFetchProfiles() {
    fetch('/api/ecn-report/profiles')
        .then(function(r) { return r.json(); })
        .then(function(profiles) {
            ecnState.profiles = profiles;
            ecnRenderProfileDropdown();
        })
        .catch(function() {});
}

// ── Target Merging ───────────────────────────────────────────────────

function ecnMergeTargets(baseline, profile) {
    var merged = {};
    for (var key in baseline) {
        merged[key] = { standard: baseline[key].standard, urgent: baseline[key].urgent };
    }
    if (profile && profile.overrides) {
        for (var key in profile.overrides) {
            if (merged[key]) {
                if (profile.overrides[key].standard !== undefined) merged[key].standard = profile.overrides[key].standard;
                if (profile.overrides[key].urgent !== undefined) merged[key].urgent = profile.overrides[key].urgent;
            } else {
                merged[key] = { standard: profile.overrides[key].standard, urgent: profile.overrides[key].urgent };
            }
        }
    }
    for (var key in ecnState.dirtyTargets) {
        if (!merged[key]) merged[key] = {};
        if (ecnState.dirtyTargets[key].standard !== undefined) merged[key].standard = ecnState.dirtyTargets[key].standard;
        if (ecnState.dirtyTargets[key].urgent !== undefined) merged[key].urgent = ecnState.dirtyTargets[key].urgent;
    }
    return merged;
}

// ── KPI Computation ──────────────────────────────────────────────────

function ecnComputeStats() {
    var ecns = ecnState.ecns;
    var targets = ecnState.currentTargets;
    var annotations = ecnState.annotations;

    var rows = ecns.map(function(e) {
        var a = annotations[e.number];
        var row = {};
        for (var k in e) row[k] = e[k];
        if (a) {
            if (a.teamOverride) row.productTeam = a.teamOverride;
            if (a.classificationOverride) row.ecnClassification = a.classificationOverride;
        }
        var tKey = (row.requestClassification || '') + '|' + (row.requestSubclass || '');
        var t = targets[tKey];
        row._targetKey = tKey;
        // PT-88 (Jimmy, 2026-06-12 follow-up): the published General-ECN SLA
        // is Std=10d / Urg=6d, same shape as every other classification.
        // The "uniform target for General ECNs" branch (PT-59 → PT-84 → PT-87)
        // never matched the spec — Jimmy wants every row measured against the
        // matrix value for its priority, full stop. So General/Standard ECNs
        // now fall through to the same priority-based lookup as PDR / CCB / etc.
        if (t) {
            var targetDays = row.priority === 'Urgent' ? t.urgent : t.standard;
            row._targetDays = targetDays;
            if (targetDays === 'NA' || targetDays === 'TBD' || targetDays === null || targetDays === undefined) {
                row._onTarget = null;
                row._dayDiff = null;
            } else {
                var tdNum = parseFloat(targetDays);
                row._onTarget = row.actualDays <= tdNum;
                row._dayDiff = row.actualDays !== null && row.actualDays !== undefined
                    ? Math.round((row.actualDays - tdNum) * 10) / 10
                    : null;
            }
        } else {
            row._targetDays = null;
            row._onTarget = null;
            row._dayDiff = null;
        }
        return row;
    });

    var completed = rows.filter(function(r) { return r.status === 'Completed'; });
    var grandTotal = completed.length;
    var stats = {};
    stats.volume = ecnComputeVolumePanel(completed, grandTotal);
    var generalSet = completed.filter(function(r) {
        var c = r.ecnClassification;
        return c === 'Standard ECNs' || c === 'General ECNs';
    });
    var pdrSet = completed.filter(function(r) { return (r.ecnClassification || '').indexOf('PDR') >= 0; });
    var dedicatedSet = completed.filter(function(r) { return (r.ecnClassification || '').indexOf('Dedicated') >= 0; });
    stats.ctGeneral = ecnComputeCycleTimePanel(generalSet, grandTotal);
    stats.ctPdr = ecnComputeCycleTimePanel(pdrSet, grandTotal);
    stats.cbsGeneral = ecnComputeCycleByStatusPanel(generalSet);
    stats.cbsPdr = ecnComputeCycleByStatusPanel(pdrSet);
    stats.cbsDedicated = ecnComputeCycleByStatusPanel(dedicatedSet);
    stats.volumeByType = ecnComputeVolumeByType(completed, grandTotal);
    stats.ctDedicated = ecnComputeBySubclass(dedicatedSet, grandTotal);
    stats.ctTeam = ecnComputeTeamPanel(completed, grandTotal);
    stats.pom = ecnComputePomPanel(completed);
    stats.trends = ecnComputeTrends(completed);
    // PT-74: Jimmy asked for a quarterly view under each cycle-time panel.
    // PT-77: window is now "last 3 completed quarters" (not prev/last/current),
    // metric is % on Target instead of Avg Days for cycle-time charts. Stash on
    // ecnState so chart-draw fns can read without changing every panel signature.
    stats.quarterlyTrends = ecnComputeQuarterlyTrends(completed);
    ecnState.quarterlyTrends = stats.quarterlyTrends;
    // PT-77: Team chart is now monthly volume by team (top 6 + Others) instead
    // of avg-cycle-days. Quarterly version follows the same shape using last-3
    // completed quarters.
    var topTeams = ecnPickTopTeams(completed);
    stats.teamMonthly = ecnComputeTeamMonthly(completed, topTeams);
    stats.teamQuarterly = ecnComputeTeamQuarterly(completed, topTeams);
    ecnState.teamMonthly = stats.teamMonthly;
    ecnState.teamQuarterly = stats.teamQuarterly;
    stats.cycleByStatus = ecnComputeCycleByStatusPanel(completed);
    stats.allRows = rows;
    stats.totalCompleted = grandTotal;
    return stats;
}

// Per-workflow-status cycle time (Submitted/Review/Release/Hold) for completed ECNs.
// Source field is daysAt<Status> emitted by the Python sidecar. Pending was
// removed from the aggregate panel on 2026-05-12 per PT-44 — the per-row data
// table still exposes daysAtPending (D@Pend column) for anyone debugging.
function ecnComputeCycleByStatusPanel(completed) {
    var statuses = [
        { key: 'daysAtSubmitted', label: 'Submitted' },
        { key: 'daysAtReview',    label: 'Review'    },
        { key: 'daysAtRelease',   label: 'Release'   },
        { key: 'daysAtHold',      label: 'Hold'      }
    ];
    // PT-106 (Jimmy, 2026-07-02): split each workflow status by priority so the
    // reader can see which stage impacts Standard vs Urgent ECNs, not just a blended
    // average. Each tile now carries std/urg sub-stats alongside the overall.
    function statFor(rows, key) {
        var vals = [];
        for (var j = 0; j < rows.length; j++) {
            var v = rows[j][key];
            if (v !== null && v !== undefined) vals.push(v);
        }
        if (!vals.length) return { avg: null, min: null, max: null, count: 0 };
        var sum = 0;
        for (var k = 0; k < vals.length; k++) sum += vals[k];
        return {
            avg: Math.round(sum / vals.length * 10) / 10,
            min: Math.min.apply(null, vals),
            max: Math.max.apply(null, vals),
            count: vals.length
        };
    }
    var stdRows = completed.filter(function(r) { return r.priority === 'Standard'; });
    var urgRows = completed.filter(function(r) { return r.priority === 'Urgent'; });
    var out = [];
    for (var i = 0; i < statuses.length; i++) {
        var s = statuses[i];
        var overall = statFor(completed, s.key);
        out.push({
            label: s.label,
            avg: overall.avg, min: overall.min, max: overall.max, count: overall.count,
            standard: statFor(stdRows, s.key),
            urgent: statFor(urgRows, s.key)
        });
    }
    return out;
}

function ecnComputeVolumePanel(ecns, grandTotal) {
    var total = ecns.length;
    var standard = ecns.filter(function(r) { return r.priority === 'Standard'; });
    var urgent = ecns.filter(function(r) { return r.priority === 'Urgent'; });
    return {
        total: total,
        pctOfOverall: grandTotal ? Math.round(total / grandTotal * 100) : 0,
        standard: { count: standard.length, pct: grandTotal ? Math.round(standard.length / grandTotal * 100) : 0 },
        urgent: { count: urgent.length, pct: grandTotal ? Math.round(urgent.length / grandTotal * 100) : 0 }
    };
}

function ecnComputeCycleTimePanel(ecns, grandTotal) {
    var total = ecns.length;
    var standard = ecns.filter(function(r) { return r.priority === 'Standard'; });
    var urgent = ecns.filter(function(r) { return r.priority === 'Urgent'; });

    function avg(arr) {
        if (arr.length === 0) return 0;
        var sum = 0;
        for (var i = 0; i < arr.length; i++) sum += (arr[i].actualDays || 0);
        return Math.round(sum / arr.length * 10) / 10;
    }
    function pctOnTarget(arr) {
        var trackable = arr.filter(function(r) { return r._onTarget !== null; });
        if (trackable.length === 0) return null;
        var onTarget = trackable.filter(function(r) { return r._onTarget; }).length;
        return Math.round(onTarget / trackable.length * 100);
    }

    return {
        total: total,
        pctOfOverall: grandTotal ? Math.round(total / grandTotal * 100) : 0,
        avgDays: avg(ecns),
        avgTargetDays: ecnAvgTargetDays(ecns),
        pctOnTarget: pctOnTarget(ecns),
        standard: {
            count: standard.length,
            pct: grandTotal ? Math.round(standard.length / grandTotal * 100) : 0,
            avgDays: avg(standard),
            avgTargetDays: ecnAvgTargetDays(standard),
            pctOnTarget: pctOnTarget(standard)
        },
        urgent: {
            count: urgent.length,
            pct: grandTotal ? Math.round(urgent.length / grandTotal * 100) : 0,
            avgDays: avg(urgent),
            avgTargetDays: ecnAvgTargetDays(urgent),
            pctOnTarget: pctOnTarget(urgent)
        }
    };
}

/** PT-83 (Jimmy Sessumes): mean of `_targetDays` across rows that have a
 *  numeric target. Skips rows where the SLA is null / NA / TBD so the
 *  number lines up apples-to-apples with the % on Target denominator. */
function ecnAvgTargetDays(arr) {
    var trackable = [];
    for (var i = 0; i < arr.length; i++) {
        var td = arr[i]._targetDays;
        if (td === null || td === undefined || td === 'NA' || td === 'TBD') continue;
        var n = parseFloat(td);
        if (!isNaN(n)) trackable.push(n);
    }
    if (trackable.length === 0) return null;
    var sum = 0;
    for (var j = 0; j < trackable.length; j++) sum += trackable[j];
    return Math.round(sum / trackable.length * 10) / 10;
}

function ecnComputeBySubclass(ecns, grandTotal) {
    // Group by Request Subclass (e.g., "Firmware Release/Update With SDA")
    var groups = {};
    for (var i = 0; i < ecns.length; i++) {
        var key = ecns[i].requestSubclass || '(no subclass)';
        if (!groups[key]) groups[key] = [];
        groups[key].push(ecns[i]);
    }
    var totalAcrossPanel = ecns.length;
    var totalSum = 0;
    var totalDaysCount = 0;
    for (var k = 0; k < ecns.length; k++) {
        if (ecns[k].actualDays !== null && ecns[k].actualDays !== undefined) {
            totalSum += ecns[k].actualDays;
            totalDaysCount++;
        }
    }
    var result = {
        total: totalAcrossPanel,
        pctOfOverall: grandTotal ? Math.round(totalAcrossPanel / grandTotal * 100) : 0,
        avgDays: totalDaysCount ? Math.round(totalSum / totalDaysCount * 10) / 10 : 0,
        subclasses: []
    };
    for (var key in groups) {
        var arr = groups[key];
        var sum = 0;
        for (var j = 0; j < arr.length; j++) sum += (arr[j].actualDays || 0);
        result.subclasses.push({
            subclass: key,
            count: arr.length,
            pct: grandTotal ? Math.round(arr.length / grandTotal * 100) : 0,
            avgDays: arr.length ? Math.round(sum / arr.length * 10) / 10 : 0
        });
    }
    result.subclasses.sort(function(a, b) { return b.count - a.count; });
    return result;
}

function ecnComputeVolumeByType(ecns, grandTotal) {
    var groups = {};
    for (var i = 0; i < ecns.length; i++) {
        var ct = ecns[i].changeType || 'Unknown';
        if (!groups[ct]) groups[ct] = [];
        groups[ct].push(ecns[i]);
    }
    var result = [];
    for (var ct in groups) {
        var arr = groups[ct];
        var sum = 0;
        for (var j = 0; j < arr.length; j++) sum += (arr[j].actualDays || 0);
        result.push({
            changeType: ct,
            count: arr.length,
            pct: grandTotal ? Math.round(arr.length / grandTotal * 100) : 0,
            avgDays: arr.length ? Math.round(sum / arr.length * 10) / 10 : 0
        });
    }
    result.sort(function(a, b) { return b.count - a.count; });
    return result;
}

function ecnComputeTeamPanel(ecns, grandTotal) {
    var groups = {};
    for (var i = 0; i < ecns.length; i++) {
        var team = ecns[i].productTeam || 'Unknown';
        // Bucket any multi-team value (e.g., "CS; CSSD") as "Mix"
        if (team.indexOf(';') >= 0) team = 'Mix';
        if (!groups[team]) groups[team] = [];
        groups[team].push(ecns[i]);
    }
    var result = [];
    for (var team in groups) {
        var arr = groups[team];
        var standard = arr.filter(function(r) { return r.priority === 'Standard'; });
        var urgent = arr.filter(function(r) { return r.priority === 'Urgent'; });
        var sum = 0;
        for (var j = 0; j < arr.length; j++) sum += (arr[j].actualDays || 0);
        var trackable = arr.filter(function(r) { return r._onTarget !== null; });
        var onTarget = trackable.filter(function(r) { return r._onTarget; }).length;
        function subStats(sub) {
            var s = 0; for (var k = 0; k < sub.length; k++) s += (sub[k].actualDays || 0);
            var tr = sub.filter(function(r) { return r._onTarget !== null; });
            var ot = tr.filter(function(r) { return r._onTarget; }).length;
            return {
                count: sub.length,
                pct: grandTotal ? Math.round(sub.length / grandTotal * 100) : 0,
                avgDays: sub.length ? Math.round(s / sub.length * 10) / 10 : 0,
                avgTargetDays: ecnAvgTargetDays(sub),
                pctOnTarget: tr.length ? Math.round(ot / tr.length * 100) : null
            };
        }
        result.push({
            team: team,
            count: arr.length,
            pct: grandTotal ? Math.round(arr.length / grandTotal * 100) : 0,
            avgTargetDays: ecnAvgTargetDays(arr),
            avgDays: arr.length ? Math.round(sum / arr.length * 10) / 10 : 0,
            pctOnTarget: trackable.length ? Math.round(onTarget / trackable.length * 100) : null,
            standard: subStats(standard),
            urgent: subStats(urgent)
        });
    }
    result.sort(function(a, b) { return b.count - a.count; });
    return result;
}

// PT-91 (Jimmy, 2026-06-12): the POM month-by-month KPI is the one panel that
// uses a FLAT 10-day target for every General ECN regardless of priority.
// Rationale: this is the monthly POR roll-up Jimmy reports out — he wants the
// whole bucket measured against the easier bar so progress against the target
// reads consistently across months. The main Cycle Time table keeps its
// priority-based per-row targets (Std=10, Urg=6) as published in the SLA matrix.
var POM_FLAT_TARGET_DAYS = 10;

function ecnComputePomPanel(ecns) {
    // General ECN type only — PDR and other classifications excluded.
    var standardEcns = ecns.filter(ecnIsStandardEcn);
    var months = ecnGetLast3Months();
    var result = [];
    for (var i = 0; i < months.length; i++) {
        var m = months[i];
        var monthEcns = standardEcns.filter(function(r) {
            return r.completedDate && r.completedDate.substring(0, 7) === m.key;
        });
        var total = monthEcns.length;
        var sum = 0;
        var trackable = 0;
        var onTarget = 0;
        for (var j = 0; j < monthEcns.length; j++) {
            var r = monthEcns[j];
            sum += (r.actualDays || 0);
            // PT-91: ignore r._onTarget (which is priority-based) and re-evaluate
            // every row against the flat POM target.
            if (r.actualDays !== null && r.actualDays !== undefined) {
                trackable++;
                if (r.actualDays <= POM_FLAT_TARGET_DAYS) onTarget++;
            }
        }
        result.push({
            label: m.label,
            key: m.key,
            total: total,
            avgDays: total ? Math.round(sum / total * 10) / 10 : 0,
            pctOnTarget: trackable ? Math.round(onTarget / trackable * 100) : null
        });
    }
    return result;
}

/**
 * Month-to-date comparison helper. Computes Completed-ECN aggregates for two
 * windows: (a) current calendar month from day 1 to today's day-of-month, and
 * (b) prior calendar month from day 1 to the SAME day-of-month (clamped to the
 * prior month's last day if shorter, e.g. May 31 → Apr 30).
 *
 * Powers the apples-to-apples KPI tile delta. Replaces the old "last full
 * month vs prior full month" delta which was unfair while the current month
 * was still in progress (May 6 showing 5 days of data was being compared to
 * April's 30 days, producing a meaningless "↓ 95%" headline).
 */
function ecnComputeMtdDelta(allRows) {
    var asOf = ecnState && ecnState.dateRange && ecnState.dateRange.end
        ? new Date(ecnState.dateRange.end + 'T00:00:00') : new Date();
    var asOfDay = asOf.getDate();
    function pad(n) { return n < 10 ? '0' + n : '' + n; }
    function ymOf(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1); }

    var currYM = ymOf(asOf);
    var prevDate = new Date(asOf.getFullYear(), asOf.getMonth() - 1, 1);
    var prevYM = ymOf(prevDate);
    // Last day of the prior month: day-0 of current month
    var prevMonthLastDay = new Date(asOf.getFullYear(), asOf.getMonth(), 0).getDate();
    var compareDay = Math.min(asOfDay, prevMonthLastDay);

    function bucket(rows, monthKey, maxDay) {
        var filtered = rows.filter(function(r) {
            if (r.status !== 'Completed') return false;
            if (!r.completedDate || r.completedDate.length < 10) return false;
            if (r.completedDate.substring(0, 7) !== monthKey) return false;
            var day = parseInt(r.completedDate.substring(8, 10), 10);
            return day <= maxDay;
        });
        var total = filtered.length;
        var urgent = 0, sumDays = 0, trackable = 0, onTarget = 0;
        for (var i = 0; i < filtered.length; i++) {
            var r = filtered[i];
            if (r.priority === 'Urgent') urgent++;
            sumDays += (r.actualDays || 0);
            if (r._onTarget !== null && r._onTarget !== undefined) {
                trackable++;
                if (r._onTarget) onTarget++;
            }
        }
        return {
            total: total,
            urgent: urgent,
            avgDays: total ? Math.round(sumDays / total * 10) / 10 : 0,
            pctOnTarget: trackable ? Math.round(onTarget / trackable * 100) : null,
            urgentShare: total ? Math.round(urgent / total * 1000) / 10 : 0
        };
    }

    // Human-readable label for the tile suffix, e.g. "May 1-6 vs Apr 1-6"
    var monthNames = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    var compareLabel = monthNames[asOf.getMonth()] + ' 1-' + asOfDay
        + ' vs ' + monthNames[prevDate.getMonth()] + ' 1-' + compareDay;

    return {
        curr: bucket(allRows, currYM, asOfDay),
        prev: bucket(allRows, prevYM, compareDay),
        asOfDay: asOfDay,
        compareDay: compareDay,
        compareLabel: compareLabel
    };
}

function ecnComputeTrends(ecns) {
    var months = ecnGetLast3Months();
    var trends = {};
    for (var i = 0; i < months.length; i++) {
        var m = months[i];
        var monthEcns = ecns.filter(function(r) {
            return r.completedDate && r.completedDate.substring(0, 7) === m.key;
        });
        var standard = monthEcns.filter(function(r) { return r.priority === 'Standard'; });
        var urgent = monthEcns.filter(function(r) { return r.priority === 'Urgent'; });

        function avgDays(arr) {
            if (!arr.length) return 0;
            var s = 0; for (var j = 0; j < arr.length; j++) s += (arr[j].actualDays || 0);
            return Math.round(s / arr.length * 10) / 10;
        }
        function pctOT(arr) {
            var t = arr.filter(function(r) { return r._onTarget !== null; });
            if (!t.length) return 0;
            return Math.round(t.filter(function(r) { return r._onTarget; }).length / t.length * 100);
        }

        trends[m.key] = {
            label: m.label,
            total: monthEcns.length,
            standard: standard.length,
            urgent: urgent.length,
            avgDays: avgDays(monthEcns),
            stdAvgDays: avgDays(standard),  // PT-76: split for grouped cycle-time chart
            urgAvgDays: avgDays(urgent),
            pctOnTarget: pctOT(monthEcns),
            stdPctOnTarget: pctOT(standard),  // PT-77: cycle-time chart now tracks % on Target
            urgPctOnTarget: pctOT(urgent)
        };
    }
    return { months: months, data: trends };
}

function ecnGetLast3Months() {
    var end = ecnState.dateRange ? new Date(ecnState.dateRange.end) : new Date();
    var months = [];
    for (var i = 2; i >= 0; i--) {
        var d = new Date(end.getFullYear(), end.getMonth() - i, 1);
        var key = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
        var label = d.toLocaleString('en-US', { month: 'short', year: 'numeric' });
        months.push({ key: key, label: label });
    }
    return months;
}

// PT-74: prev / last / current calendar quarter, anchored on dateRange.end if set
// (so the dashboard's "as of" date drives the window, not wall-clock).
function ecnGetLast3Quarters() {
    var end = ecnState.dateRange ? new Date(ecnState.dateRange.end) : new Date();
    var currQ = Math.floor(end.getMonth() / 3) + 1;
    var currY = end.getFullYear();
    var qs = [];
    for (var offset = 2; offset >= 0; offset--) {
        var q = currQ - offset;
        var y = currY;
        while (q <= 0) { q += 4; y -= 1; }
        qs.push({ key: 'Q' + q + '-' + y, label: 'Q' + q + ' ' + y });
    }
    return qs;
}

// PT-77: last three FULLY COMPLETED quarters, excluding the one we're sitting in.
// Sliding window, refreshes the day a new quarter begins. Used for the quarterly
// trends Jimmy asked to track regardless of YTD range — so the chart stays
// meaningful in early January when YTD is near-empty. e.g. on 2026-05-28 (Q2 in
// progress), returns Q3-2025 / Q4-2025 / Q1-2026.
function ecnGetLast3CompletedQuarters() {
    // PT-105 (Jimmy, 2026-07-01): anchor the "in-progress quarter" to TODAY, not to
    // ecnState.dateRange.end. Previously the window was derived from the dashboard's
    // date-range end (or the latest completedDate), so once a quarter closed but the
    // filter still ended inside it, that finished quarter was wrongly treated as
    // in-progress and dropped — the chart stayed stuck at Q1 after Q2 completed.
    // Anchoring to now() makes the window roll forward the day a new quarter begins,
    // independent of the YTD range (the PT-77 intent).
    var now = new Date();
    var currQ = Math.floor(now.getMonth() / 3) + 1;
    var currY = now.getFullYear();
    var qs = [];
    for (var offset = 3; offset >= 1; offset--) {  // offset 3,2,1 — skip the in-progress quarter (offset 0)
        var q = currQ - offset;
        var y = currY;
        while (q <= 0) { q += 4; y -= 1; }
        qs.push({ key: 'Q' + q + '-' + y, label: 'Q' + q + ' ' + y });
    }
    return qs;
}

function ecnDateToQuarterKey(dateStr) {
    if (!dateStr || dateStr.length < 7) return null;
    var y = parseInt(dateStr.substring(0, 4), 10);
    var m = parseInt(dateStr.substring(5, 7), 10);
    if (isNaN(y) || isNaN(m)) return null;
    return 'Q' + (Math.floor((m - 1) / 3) + 1) + '-' + y;
}

// PT-77: switched the window from prev/last/current (ecnGetLast3Quarters) to
// last-3-completed (ecnGetLast3CompletedQuarters), per Jimmy: the in-progress
// quarter is excluded so the chart isn't dragged down by a half-quarter sample.
// Slides forward automatically the day a new quarter begins. Also emits
// stdPctOnTarget / urgPctOnTarget per quarter — cycle-time charts now track
// % on Target instead of Avg Days.
function ecnComputeQuarterlyTrends(completed) {
    var quarters = ecnGetLast3CompletedQuarters();
    var data = {};
    function avgDays(arr) {
        if (!arr.length) return 0;
        var s = 0; for (var j = 0; j < arr.length; j++) s += (arr[j].actualDays || 0);
        return Math.round(s / arr.length * 10) / 10;
    }
    function pctOT(arr) {
        var t = arr.filter(function(r) { return r._onTarget !== null; });
        if (!t.length) return null;
        return Math.round(t.filter(function(r) { return r._onTarget; }).length / t.length * 100);
    }
    for (var i = 0; i < quarters.length; i++) {
        var q = quarters[i];
        var qEcns = completed.filter(function(r) {
            return ecnDateToQuarterKey(r.completedDate) === q.key;
        });
        // PT-76: split by priority so the quarterly chart can render grouped bars.
        var qStd = qEcns.filter(function(r) { return r.priority === 'Standard'; });
        var qUrg = qEcns.filter(function(r) { return r.priority === 'Urgent'; });
        data[q.key] = {
            label: q.label,
            total: qEcns.length,
            avgDays: avgDays(qEcns),
            stdTotal: qStd.length,
            urgTotal: qUrg.length,
            stdAvgDays: avgDays(qStd),
            urgAvgDays: avgDays(qUrg),
            stdPctOnTarget: pctOT(qStd),  // PT-77
            urgPctOnTarget: pctOT(qUrg)
        };
    }
    return { quarters: quarters, data: data };
}

// PT-77: monthly + quarterly volume by Product Team. Top N teams by total
// volume, the rest rolled up into "Others" so the chart stays readable. Both
// helpers share the same team-ranking so the legend is consistent.
var ECN_TEAM_CHART_TOP_N = 6;

function ecnPickTopTeams(completed) {
    var counts = {};
    for (var i = 0; i < completed.length; i++) {
        var team = completed[i].productTeam || 'Unknown';
        if (team.indexOf(';') >= 0) team = 'Mix';
        counts[team] = (counts[team] || 0) + 1;
    }
    var sorted = Object.keys(counts).sort(function(a, b) { return counts[b] - counts[a]; });
    return sorted.slice(0, ECN_TEAM_CHART_TOP_N);
}

function ecnTeamBucket(rec, topSet) {
    var team = rec.productTeam || 'Unknown';
    if (team.indexOf(';') >= 0) team = 'Mix';
    return topSet[team] ? team : 'Others';
}

function ecnComputeTeamMonthly(completed, topTeams) {
    var months = ecnGetLast3Months();
    var topSet = {};
    for (var t = 0; t < topTeams.length; t++) topSet[topTeams[t]] = true;
    var teamLabels = topTeams.slice();
    teamLabels.push('Others');
    var data = {};
    for (var i = 0; i < teamLabels.length; i++) data[teamLabels[i]] = {};
    for (var j = 0; j < months.length; j++) {
        var mk = months[j].key;
        for (var k = 0; k < teamLabels.length; k++) data[teamLabels[k]][mk] = 0;
    }
    for (var n = 0; n < completed.length; n++) {
        var rec = completed[n];
        if (!rec.completedDate || rec.completedDate.length < 7) continue;
        var mk2 = rec.completedDate.substring(0, 7);
        if (!data[teamLabels[0]].hasOwnProperty(mk2)) continue;  // outside the 3-month window
        var bucket = ecnTeamBucket(rec, topSet);
        data[bucket][mk2] = (data[bucket][mk2] || 0) + 1;
    }
    return { months: months, teams: teamLabels, data: data };
}

function ecnComputeTeamQuarterly(completed, topTeams) {
    var quarters = ecnGetLast3CompletedQuarters();
    var topSet = {};
    for (var t = 0; t < topTeams.length; t++) topSet[topTeams[t]] = true;
    var teamLabels = topTeams.slice();
    teamLabels.push('Others');
    var qKeys = {};
    for (var j = 0; j < quarters.length; j++) qKeys[quarters[j].key] = true;
    var data = {};
    for (var i = 0; i < teamLabels.length; i++) {
        data[teamLabels[i]] = {};
        for (var jq = 0; jq < quarters.length; jq++) data[teamLabels[i]][quarters[jq].key] = 0;
    }
    for (var n = 0; n < completed.length; n++) {
        var rec = completed[n];
        var qk = ecnDateToQuarterKey(rec.completedDate);
        if (!qk || !qKeys[qk]) continue;
        var bucket = ecnTeamBucket(rec, topSet);
        data[bucket][qk] = (data[bucket][qk] || 0) + 1;
    }
    return { quarters: quarters, teams: teamLabels, data: data };
}

// Color palette for team series — distinct enough to read in a stacked legend.
// First 6 align with the slate / blue / red / green / purple / amber theme; the
// 7th (Others) is muted grey so the rollup doesn't visually compete with the
// real teams.
var ECN_TEAM_COLORS = ['#4a6fa5', '#B8342B', '#1F8A4C', '#7C3AED', '#C7801B', '#2c3e50', '#9ca3af'];

if (!String.prototype.padStart) {
    String.prototype.padStart = function(len, ch) {
        var s = String(this); ch = ch || ' ';
        while (s.length < len) s = ch + s;
        return s;
    };
}

// ── UI Refresh ───────────────────────────────────────────────────────

function ecnRefreshUI() {
    if (!ecnState.loaded) return;
    var stats = ecnComputeStats();
    var genDate = ecnState.generatedAt ? new Date(ecnState.generatedAt).toLocaleString() : 'unknown';
    document.getElementById('ecnReportStatus').textContent =
        stats.totalCompleted + ' completed ECNs \u00b7 Generated ' + genDate;

    document.getElementById('ecnDashboard').style.display = '';
    // SLA target editing relocated to the KPI Master tab (Vikas Singh 2026-07-06) —
    // keep this legacy editable panel hidden.
    document.getElementById('ecnTargetsGroup').style.display = 'none';
    document.getElementById('ecnDataGroup').style.display = '';

    ecnRenderHeadlineStrip(stats);
    ecnRenderVolumePanel(stats.volume, stats.trends);
    ecnRenderCycleTimePanel('ecnCtGeneralContent', stats.ctGeneral, stats.trends, stats.cbsGeneral);
    ecnRenderCycleTimePanel('ecnCtPdrContent', stats.ctPdr, stats.trends, stats.cbsPdr);
    ecnRenderVolumeByTypePanel(stats.volumeByType, stats.trends);
    ecnRenderDedicatedPanel(stats.ctDedicated, stats.trends, stats.cbsDedicated);
    ecnRenderTeamPanel(stats.ctTeam, stats.trends);
    ecnRenderCycleByStatusPanel(stats.cycleByStatus);
    ecnRenderPomPanel(stats.pom);
    ecnRenderSlaReference();
    ecnUpdateDirtyBadge();
    ecnRenderTargetsTable();
    ecnRenderDataTable(stats.allRows);
    // Keep the KPI Master editor in sync when it's the visible tab (e.g. after an
    // Apply/Reset of SLA target edits made there).
    var kpiV = document.getElementById('kpiMasterView');
    if (kpiV && kpiV.style.display !== 'none' && typeof kpiMasterRender === 'function'
        && kpiMasterState && kpiMasterState.entries) {
        kpiMasterRender();
    }
}

// ── Headline KPI Strip (above the 6-card grid) ───────────────────────

function ecnRenderHeadlineStrip(stats) {
    var el = document.getElementById('ecnHeadlineStrip');
    if (!el) return;

    var totals = stats.totalCompleted;
    var avgDays = stats.ctGeneral.avgDays;
    var pctOnTarget = stats.ctGeneral.pctOnTarget;
    var urgentShare = stats.volume.total
        ? Math.round(stats.volume.urgent.count / stats.volume.total * 100)
        : 0;

    // Late (>10 days) count from row-level data
    var lateCount = 0;
    for (var i = 0; i < stats.allRows.length; i++) {
        var d = stats.allRows[i]._dayDiff;
        if (typeof d === 'number' && d > 10) lateCount++;
    }

    // Deltas: compare current MTD vs SAME PERIOD last month (apples to apples).
    // Previously this compared the last calendar month's full bucket to the
    // prior month's full bucket — but the "current" month is partial (e.g. on
    // May 6 we'd see ~5 days of May vs 30 days of April), producing a
    // headline-grade fake "↓ 95.7%" drop. Now we clamp both sides to the same
    // day-of-month range, so May 1-6 is compared to April 1-6.
    var mtd = ecnComputeMtdDelta(stats.allRows);
    var deltaTotal = (mtd.prev.total > 0)
        ? Math.round((mtd.curr.total - mtd.prev.total) / mtd.prev.total * 1000) / 10
        : null;
    var deltaPct = (mtd.curr.pctOnTarget !== null && mtd.prev.pctOnTarget !== null)
        ? Math.round((mtd.curr.pctOnTarget - mtd.prev.pctOnTarget) * 10) / 10
        : null;
    var deltaAvg = (mtd.curr.total > 0 && mtd.prev.total > 0)
        ? Math.round((mtd.curr.avgDays - mtd.prev.avgDays) * 10) / 10
        : null;
    var deltaUrgentShare = (mtd.curr.total > 0 && mtd.prev.total > 0)
        ? Math.round((mtd.curr.urgentShare - mtd.prev.urgentShare) * 10) / 10
        : null;
    // Suffix tells the user which window the delta is measured against.
    var mtdSuffix = ' vs same period last month (' + mtd.compareLabel + ')';

    function tone(value, thresholds) {
        if (value === null || value === undefined) return '#333';
        if (value >= thresholds.good) return '#1F8A4C';
        if (value >= thresholds.warn) return '#C7801B';
        return '#B8342B';
    }
    function deltaCell(value, unit, inverted, stableThreshold) {
        if (value === null || value === undefined) return '<span style="color:#9CA3AF;">—</span>';
        if (Math.abs(value) <= (stableThreshold || 0)) {
            return '<span style="color:#6B7280;">\u2192 stable</span>';
        }
        var positive = value > 0;
        var good = inverted ? !positive : positive;
        var color = good ? '#1F8A4C' : '#B8342B';
        var arrow = positive ? '\u2191' : '\u2193';
        return '<span style="color:' + color + ';"><span style="font-family:monospace; font-weight:600;">' + arrow + '</span> ' +
            Math.abs(value) + unit + '</span>';
    }

    var ptColor = tone(pctOnTarget, { good: 85, warn: 70 });

    var tiles = [
        {
            label: 'TOTAL ECNs \u00b7 YTD',
            value: totals.toLocaleString(),
            valueColor: '#1f2c4a',
            delta: deltaTotal !== null ? deltaCell(deltaTotal, '%', false, 1) : '',
            deltaSuffix: deltaTotal !== null ? mtdSuffix : ''
        },
        {
            label: '% ON TARGET',
            value: pctOnTarget !== null ? pctOnTarget + '%' : 'NA',
            valueColor: ptColor,
            delta: deltaCell(deltaPct, ' pts', false, 0.5),
            deltaSuffix: deltaPct !== null ? mtdSuffix : ''
        },
        {
            label: 'AVG NET DAYS',
            value: avgDays,
            valueColor: '#1f2c4a',
            delta: deltaCell(deltaAvg, ' days', true, 0.1),
            deltaSuffix: deltaAvg !== null ? mtdSuffix : ''
        },
        {
            label: 'URGENT SHARE',
            value: urgentShare + '%',
            valueColor: '#1f2c4a',
            delta: deltaCell(deltaUrgentShare, ' pts', false, 1),
            deltaSuffix: deltaUrgentShare !== null ? mtdSuffix : ''
        },
        {
            label: 'LATE (10+ DAYS)',
            value: lateCount.toLocaleString(),
            valueColor: lateCount > 0 ? '#B8342B' : '#1F8A4C',
            delta: '<span style="color:#6B7280;">' + Math.round(100 * lateCount / totals * 10) / 10 + '% of total</span>',
            deltaSuffix: ''
        }
    ];

    var html = '<div style="background:linear-gradient(180deg,#fff,#fbfaf6); border:1px solid #E8E6DF; border-radius:8px; display:grid; grid-template-columns:repeat(5, 1fr); overflow:hidden;">';
    for (var i = 0; i < tiles.length; i++) {
        var t = tiles[i];
        var sep = i < tiles.length - 1 ? 'border-right:1px solid #E8E6DF;' : '';
        html += '<div style="padding:14px 18px; ' + sep + '">';
        html += '<div style="font-family:monospace; font-size:10px; letter-spacing:0.1em; text-transform:uppercase; color:#6B7280; margin-bottom:6px;">' + t.label + '</div>';
        html += '<div style="font-family:monospace; font-size:24px; font-weight:600; letter-spacing:-0.02em; line-height:1; color:' + t.valueColor + ';">' + t.value + '</div>';
        if (t.delta) {
            html += '<div style="font-size:11px; margin-top:6px;">' + t.delta + '<span style="color:#9CA3AF;">' + (t.deltaSuffix || '') + '</span></div>';
        }
        html += '</div>';
    }
    html += '</div>';
    el.innerHTML = html;
    el.style.display = '';
}

// ── Panel Rendering Helpers ──────────────────────────────────────────

function ecnKpiRow(label, values) {
    var cells = '';
    for (var i = 0; i < values.length; i++) {
        var v = values[i];
        var color = v.color || '#333';
        cells += '<td style="padding:6px 10px; border:1px solid #ccc; text-align:center; font-weight:600; color:' + color + ';">' +
            (v.text !== null && v.text !== undefined ? v.text : 'NA') + '</td>';
    }
    return '<td style="padding:6px 10px; border:1px solid #ccc; font-weight:500;">' + label + '</td>' + cells;
}

function ecnPanelTable(headers, rowsHtml, canvasId) {
    var html = '<table style="width:100%; border-collapse:collapse; font-size:13px; margin-bottom:12px;">';
    html += '<tr style="background:#2c3e50; color:#fff;">';
    for (var i = 0; i < headers.length; i++) {
        html += '<th style="padding:6px 10px; border:1px solid #444; text-align:' + (i === 0 ? 'left' : 'center') + '; font-size:12px;">' + headers[i] + '</th>';
    }
    html += '</tr>';
    for (var j = 0; j < rowsHtml.length; j++) {
        var bg = j % 2 === 0 ? '#fff' : '#FAFAF7';
        html += '<tr style="background:' + bg + ';">' + rowsHtml[j] + '</tr>';
    }
    html += '</table>';
    if (canvasId) {
        html += '<div id="' + canvasId + 'Wrap" style="width:100%;"><div style="position:relative; height:140px; width:100%;"><canvas id="' + canvasId + '"></canvas></div></div>';
    }
    return html;
}

function ecnPctColor(pct) {
    if (pct === null || pct === undefined) return '#6B7280';
    return pct >= 80 ? '#1F8A4C' : '#B8342B';
}

// Compact 5-tile strip used inside the per-classification cycle time cards
// (Standard / PDR / Dedicated Process). Renders one tile per tracked status.
function ecnCycleByStatusStripHtml(panel) {
    // PT-106 (Jimmy, 2026-07-02): each status tile now shows Standard vs Urgent avg
    // days side-by-side (Std in ink, Urg in the urgent-red accent) instead of one
    // blended number, so it's clear which priority is stalling at each stage.
    function fmt(x) { return (x !== null && x !== undefined) ? x : '\u2014'; }
    var html = '<div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:0.05em; font-weight:600; margin:4px 0 2px;">Avg Days by Workflow Status</div>';
    html += '<div style="font-size:9px; color:#6B7280; margin:0 0 6px;">Standard vs <span style="color:#B8342B;">Urgent</span></div>';
    html += '<div style="display:grid; grid-template-columns:repeat(' + panel.length + ',1fr); gap:6px; margin-bottom:8px;">';
    for (var i = 0; i < panel.length; i++) {
        var t = panel[i];
        var s = t.standard || { avg: t.avg, count: t.count };
        var u = t.urgent || { avg: null, count: 0 };
        html += '<div style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:5px; padding:6px 4px; text-align:center;">' +
            '<div style="font-size:9px; color:#6B7280; text-transform:uppercase; letter-spacing:0.04em; font-weight:600; margin-bottom:3px;">' + t.label + '</div>' +
            '<div style="display:flex; gap:4px;">' +
                '<div style="flex:1;">' +
                    '<div style="font-size:8px; color:#6B7280; text-transform:uppercase; letter-spacing:0.03em;">Std</div>' +
                    '<div style="font-size:16px; font-weight:bold; color:#0F1720; line-height:1.1;">' + fmt(s.avg) + '</div>' +
                    '<div style="font-size:8px; color:#6B7280;">' + (s.count > 0 ? 'n=' + s.count : '\u2014') + '</div>' +
                '</div>' +
                '<div style="flex:1; border-left:1px solid #E8E6DF;">' +
                    '<div style="font-size:8px; color:#6B7280; text-transform:uppercase; letter-spacing:0.03em;">Urg</div>' +
                    '<div style="font-size:16px; font-weight:bold; color:#B8342B; line-height:1.1;">' + fmt(u.avg) + '</div>' +
                    '<div style="font-size:8px; color:#6B7280;">' + (u.count > 0 ? 'n=' + u.count : '\u2014') + '</div>' +
                '</div>' +
            '</div>' +
            '</div>';
    }
    html += '</div>';
    return html;
}

// ── Volume Panel ─────────────────────────────────────────────────────

function ecnRenderVolumePanel(vol, trends) {
    var headers = ['Value', 'Total', '% of Overall'];
    var rows = [];
    rows.push(ecnKpiRow('Total Completed ECNs', [{ text: vol.total }, { text: 'NA' }]));
    rows.push(ecnKpiRow('Standard', [{ text: vol.standard.count }, { text: vol.standard.pct + '%' }]));
    rows.push(ecnKpiRow('Urgent', [{ text: vol.urgent.count }, { text: vol.urgent.pct + '%' }]));
    document.getElementById('ecnVolContent').innerHTML = ecnPanelTable(headers, rows, 'ecnChartVol');
    ecnDrawVolumeChart('ecnChartVol', trends);
}

// ── Cycle Time Panel (General, PDR, Dedicated) ───────────────────────

function ecnRenderCycleTimePanel(containerId, ct, trends, cycleByStatus) {
    // PT-83 (Jimmy): "Target Days" column between Avg Days and % on Target so
    // the reader can eyeball actual-vs-target side-by-side. Cell shows the mean
    // of `_targetDays` across that bucket's rows; NA when no SLA was numeric.
    var headers = ['Value', 'Total', '% of Overall', 'Avg Days', 'Target Days', '% on Target'];
    var rows = [];
    rows.push(ecnKpiRow('Total Completed ECNs', [
        { text: ct.total }, { text: ct.pctOfOverall + '%' },
        { text: ct.avgDays },
        // PT-97 (Jimmy, 2026-06-23): the total row mixes Standard + Urgent targets,
        // so a single averaged Target Days is misleading. Show NA here; the Standard
        // and Urgent rows below still carry their own target.
        { text: 'NA' },
        { text: ct.pctOnTarget !== null ? ct.pctOnTarget + '%' : 'NA', color: ecnPctColor(ct.pctOnTarget) }
    ]));
    rows.push(ecnKpiRow('Standard', [
        { text: ct.standard.count }, { text: ct.standard.pct + '%' },
        { text: ct.standard.avgDays },
        { text: ct.standard.avgTargetDays !== null && ct.standard.avgTargetDays !== undefined ? ct.standard.avgTargetDays : 'NA' },
        { text: ct.standard.pctOnTarget !== null ? ct.standard.pctOnTarget + '%' : 'NA', color: ecnPctColor(ct.standard.pctOnTarget) }
    ]));
    rows.push(ecnKpiRow('Urgent', [
        { text: ct.urgent.count }, { text: ct.urgent.pct + '%' },
        { text: ct.urgent.avgDays },
        { text: ct.urgent.avgTargetDays !== null && ct.urgent.avgTargetDays !== undefined ? ct.urgent.avgTargetDays : 'NA' },
        { text: ct.urgent.pctOnTarget !== null ? ct.urgent.pctOnTarget + '%' : 'NA', color: ecnPctColor(ct.urgent.pctOnTarget) }
    ]));
    var canvasId = containerId.replace('Content', 'Chart');
    var html = ecnPanelTable(headers, rows, canvasId);
    if (cycleByStatus) html += ecnCycleByStatusStripHtml(cycleByStatus);
    document.getElementById(containerId).innerHTML = html;
    ecnDrawCycleTimeChart(canvasId, trends);
}

// ── Volume by Change Type ────────────────────────────────────────────

var ecnVolTypeExpanded = false;
var ECN_VOLTYPE_PREVIEW = 4;

// ── Dedicated Process Panel (subclass breakdown, no % on Target column) ─

function ecnRenderDedicatedPanel(data, trends, cycleByStatus) {
    var headers = ['Request Subclass', 'Total', '% of Overall', 'Avg Days'];
    var rows = [];
    rows.push(ecnKpiRow('Total Completed ECNs', [
        { text: data.total },
        { text: data.pctOfOverall + '%' },
        { text: data.avgDays }
    ]));
    for (var i = 0; i < data.subclasses.length; i++) {
        var s = data.subclasses[i];
        rows.push(ecnKpiRow('\u00a0\u00a0' + s.subclass, [
            { text: s.count },
            { text: s.pct + '%' },
            { text: s.avgDays }
        ]));
    }
    var html = ecnPanelTable(headers, rows, 'ecnCtDedicatedChart');
    if (cycleByStatus) html += ecnCycleByStatusStripHtml(cycleByStatus);
    document.getElementById('ecnCtDedicatedContent').innerHTML = html;
    ecnDrawCycleTimeChart('ecnCtDedicatedChart', trends);
}

function ecnRenderVolumeByTypePanel(types, trends) {
    var headers = ['Change Type', 'Total', '% of Overall', 'Avg Days'];
    var limit = ecnVolTypeExpanded ? types.length : Math.min(ECN_VOLTYPE_PREVIEW, types.length);
    var rows = [];
    for (var i = 0; i < limit; i++) {
        var t = types[i];
        rows.push(ecnKpiRow(t.changeType, [{ text: t.count }, { text: t.pct + '%' }, { text: t.avgDays }]));
    }
    // No chart — duplicates ECN Volume YTD trend
    var tableHtml = ecnPanelTable(headers, rows, null);
    if (types.length > ECN_VOLTYPE_PREVIEW) {
        if (!ecnVolTypeExpanded) {
            tableHtml += '<div style="text-align:center; padding:6px; cursor:pointer; color:#4a6fa5; font-size:12px;" onclick="ecnVolTypeExpanded=true; ecnRefreshUI();">Show all ' + types.length + ' types \u25bc</div>';
        } else {
            tableHtml += '<div style="text-align:center; padding:6px; cursor:pointer; color:#4a6fa5; font-size:12px;" onclick="ecnVolTypeExpanded=false; ecnRefreshUI();">Show top ' + ECN_VOLTYPE_PREVIEW + ' only \u25b2</div>';
        }
    }
    document.getElementById('ecnVolTypeContent').innerHTML = tableHtml;
}

// ── Product Team Panel ───────────────────────────────────────────────

var ecnTeamExpanded = false;
var ECN_TEAM_PREVIEW = 4; // show top N teams initially

function ecnRenderTeamPanel(teams, trends) {
    // PT-84 (Jimmy, 2026-06-10): Target Days column dropped from the Team
    // panel \u2014 each team mixes General + PDR ECNs with different target days,
    // so a single column would be a misleading blended number. The General-
    // only Cycle Time panel still has it.
    var headers = ['Team', 'Total', '% of Overall', 'Avg Days', '% on Target'];
    var limit = ecnTeamExpanded ? teams.length : Math.min(ECN_TEAM_PREVIEW, teams.length);
    var rows = [];
    for (var i = 0; i < limit; i++) {
        var t = teams[i];
        rows.push(ecnKpiRow(t.team, [
            { text: t.count }, { text: t.pct + '%' }, { text: t.avgDays },
            { text: t.pctOnTarget !== null ? t.pctOnTarget + '%' : 'NA', color: ecnPctColor(t.pctOnTarget) }
        ]));
        rows.push(ecnKpiRow('\u00a0\u00a0Standard', [
            { text: t.standard.count }, { text: t.standard.pct + '%' }, { text: t.standard.avgDays },
            { text: t.standard.pctOnTarget !== null ? t.standard.pctOnTarget + '%' : 'NA', color: ecnPctColor(t.standard.pctOnTarget) }
        ]));
        rows.push(ecnKpiRow('\u00a0\u00a0Urgent', [
            { text: t.urgent.count }, { text: t.urgent.pct + '%' }, { text: t.urgent.avgDays },
            { text: t.urgent.pctOnTarget !== null ? t.urgent.pctOnTarget + '%' : 'NA', color: ecnPctColor(t.urgent.pctOnTarget) }
        ]));
    }
    var tableHtml = ecnPanelTable(headers, rows, 'ecnChartCtTeam');
    if (teams.length > ECN_TEAM_PREVIEW) {
        if (!ecnTeamExpanded) {
            tableHtml += '<div style="text-align:center; padding:6px; cursor:pointer; color:#4a6fa5; font-size:12px;" onclick="ecnTeamExpanded=true; ecnRefreshUI();">Show all ' + teams.length + ' teams \u25bc</div>';
        } else {
            tableHtml += '<div style="text-align:center; padding:6px; cursor:pointer; color:#4a6fa5; font-size:12px;" onclick="ecnTeamExpanded=false; ecnRefreshUI();">Show top ' + ECN_TEAM_PREVIEW + ' only \u25b2</div>';
        }
    }
    document.getElementById('ecnCtTeamContent').innerHTML = tableHtml;
    // PT-77: Team panel's chart switched from cycle-time-by-priority to
    // monthly volume by team (top 6 + Others) per Jimmy's spec. The detail
    // table above (Std/Urg breakdown with Avg Days + % on Target) is unchanged.
    ecnDrawTeamVolumeChart('ecnChartCtTeam', ecnState.teamMonthly);
}

// ── Cycle Time by Workflow Status (Completed only) ───────────────────

function ecnRenderCycleByStatusPanel(panel) {
    var el = document.getElementById('ecnCycleByStatusContent');
    if (!el) return;
    var html = '<div style="display:grid; grid-template-columns:repeat(5,1fr); gap:12px; padding:8px;">';
    for (var i = 0; i < panel.length; i++) {
        var t = panel[i];
        var avg = (t.avg !== null && t.avg !== undefined) ? t.avg : '\u2014';
        var range = (t.count > 0) ? ('range ' + t.min + '\u2013' + t.max) : 'no data';
        html += '<div style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:14px 10px; text-align:center;">' +
            '<div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:0.05em; font-weight:600;">' + t.label + '</div>' +
            '<div style="font-size:26px; font-weight:bold; color:#0F1720; margin-top:6px; line-height:1;">' + avg +
                ' <span style="font-size:11px; color:#6B7280; font-weight:normal;">days</span></div>' +
            '<div style="font-size:10px; color:#6B7280; margin-top:6px;">' + range + ' \u00b7 n=' + t.count + '</div>' +
            '</div>';
    }
    html += '</div>';
    html += '<div style="font-size:11px; color:#6B7280; padding:0 8px 8px;">Average business days each completed ECN spent at each workflow status (from Agile change history).</div>';
    el.innerHTML = html;
}

// ── SLA Reference Strip ──────────────────────────────────────────────

function ecnRenderSlaReference() {
    var targets = ecnState.currentTargets;
    var el = document.getElementById('ecnSlaReference');
    if (!el) return;

    // Group targets by ECN Classification, tracking the individual mappings
    var classGroups = {};
    for (var key in targets) {
        var t = targets[key];
        var std = t.standard;
        var urg = t.urgent;
        var baseEntry = ecnState.baselineTargets[key];
        var cls = '';
        if (baseEntry && baseEntry.ecnClassification) {
            cls = baseEntry.ecnClassification;
        } else if (std === 'NA' || urg === 'NA') {
            cls = 'Not Tracked';
        } else if (std === 15) {
            cls = 'PDR ECNs';
        } else {
            cls = 'Standard ECNs';
        }
        // PT-89 (Jimmy, 2026-06-12): display "General ECNs" to match the
        // Cycle Time panel terminology. The SLA matrix CSV still carries the
        // legacy "Standard ECN" / "Standard ECNs" labels; normalize at the
        // banner so the rename is purely cosmetic (no data file edits needed).
        if (cls === 'Standard ECNs' || cls === 'Standard ECN') {
            cls = 'General ECNs';
        }
        if (!classGroups[cls]) classGroups[cls] = { targets: new Set(), count: 0, mappings: [] };
        classGroups[cls].targets.add(std + '/' + urg);
        classGroups[cls].count++;
        var parts = key.split('|');
        classGroups[cls].mappings.push({
            reqClass: parts[0] || '',
            subclass: parts[1] || '',
            standard: std,
            urgent: urg
        });
    }

    // Sort mappings within each group
    for (var cls in classGroups) {
        classGroups[cls].mappings.sort(function(a, b) {
            var cmp = a.reqClass.localeCompare(b.reqClass);
            return cmp !== 0 ? cmp : a.subclass.localeCompare(b.subclass);
        });
    }

    // Build the active profile label
    var profileLabel = 'Baseline (Excel Template)';
    if (ecnState.activeProfile) {
        profileLabel = ecnState.activeProfile.name + (ecnState.activeProfile.active ? ' \u2605' : '');
    }
    var dirtyCount = Object.keys(ecnState.dirtyTargets).length;
    if (dirtyCount > 0) {
        profileLabel += ' (+' + dirtyCount + ' unsaved edits)';
    }

    var html = '<div style="background:#f8f9fa; border:1px solid #E8E6DF; border-radius:8px; padding:14px 20px;">';
    html += '<div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:8px;">';
    html += '<span style="font-size:14px; font-weight:600; color:#333;">SLA Targets in Effect</span>';
    // SLA target editing relocated to the KPI Master tab (Vikas Singh 2026-07-06).
    // This strip is a read-only reference; the profile-label / apply-bar ids now live
    // in KPI Master, so they are intentionally NOT emitted here.
    html += '<span style="font-size:12px; color:#6B7280;">Profile: <strong>' + profileLabel + '</strong>' +
        ' &middot; <a href="javascript:ecnSwitchView(\'kpimaster\')" style="color:#4a6fa5; text-decoration:none; font-weight:600;">Manage in KPI Master &rarr;</a></span>';
    html += '</div>';
    html += '<div style="display:flex; flex-wrap:wrap; gap:10px;">';

    var order = ['Standard ECNs', 'General ECNs', 'PDR ECNs', "PDR ECN's", "CCB ECN's", "Dedicated Process ECN's", "Project ECN's", 'Factory Changes', 'Not Tracked'];
    var shown = {};
    var pillIdx = 0;
    for (var i = 0; i < order.length; i++) {
        var cls = order[i];
        var g = classGroups[cls];
        if (!g) continue;
        shown[cls] = true;
        html += ecnSlaPill(cls, g, pillIdx++);
    }
    for (var cls in classGroups) {
        if (shown[cls]) continue;
        html += ecnSlaPill(cls, classGroups[cls], pillIdx++);
    }

    html += '</div>';

    // Detail panel (hidden, toggled by clicking pills)
    html += '<div id="ecnSlaDetail" style="display:none; margin-top:10px;"></div>';

    html += '<div style="margin-top:6px; font-size:11px; color:#9CA3AF;">Target days = business days (NETWORKDAYS) between Submit and Final Complete. Click a category above to see its classification mappings.</div>';
    html += '</div>';

    el.innerHTML = html;
    el.style.display = '';

    // Store mappings for click handler
    window._ecnSlaGroups = classGroups;
}

function ecnSlaPill(cls, g, idx) {
    var targetList = Array.from(g.targets);
    var displayTargets = targetList.join(', ');
    var pillBg = '#e8f0fe'; var pillColor = '#1a3a5c'; var pillBorder = '#b8d4f0';
    if (cls === 'Not Tracked' || displayTargets.indexOf('NA') >= 0) {
        pillBg = '#f3f4f6'; pillColor = '#6B7280'; pillBorder = '#d1d5db';
    } else if (cls.indexOf('PDR') >= 0) {
        pillBg = '#fff3cd'; pillColor = '#856404'; pillBorder = '#ffeaa7';
    }
    var html = '<div style="background:' + pillBg + '; border:1px solid ' + pillBorder + '; border-radius:6px; padding:6px 12px; font-size:12px; color:' + pillColor + '; cursor:pointer; transition:box-shadow 0.15s;" ';
    html += 'onclick="ecnToggleSlaDetail(\'' + cls.replace(/'/g, "\\'") + '\')" ';
    html += 'title="Click to see classifications">';
    html += '<strong>' + cls + '</strong>';
    html += '<span style="margin-left:6px;">';
    if (displayTargets.indexOf('NA') >= 0) {
        html += 'No target';
    } else {
        // PT-88: every classification (including General ECNs) reads its
        // target straight from the SLA matrix mapping. No code overrides.
        var parts = targetList[0].split('/');
        html += 'Std \u2264' + parts[0] + 'd, Urg \u2264' + parts[1] + 'd';
        if (targetList.length > 1) html += ' (varies)';
    }
    html += '</span>';
    html += ' <span style="opacity:0.6;">(' + g.count + ')</span>';
    html += '</div>';
    return html;
}

var _ecnSlaDetailOpen = null;

function ecnToggleSlaDetail(cls) {
    var detailEl = document.getElementById('ecnSlaDetail');
    if (!detailEl) return;

    // Toggle off if same pill clicked again
    if (_ecnSlaDetailOpen === cls) {
        detailEl.style.display = 'none';
        _ecnSlaDetailOpen = null;
        return;
    }
    _ecnSlaDetailOpen = cls;

    var g = window._ecnSlaGroups[cls];
    if (!g) return;

    var mappings = g.mappings;

    // Group by Request Classification for readability
    var byReqClass = {};
    for (var i = 0; i < mappings.length; i++) {
        var m = mappings[i];
        var rc = m.reqClass || '(none)';
        if (!byReqClass[rc]) byReqClass[rc] = [];
        byReqClass[rc].push(m);
    }

    // Relocated: SLA target editing now lives in the KPI Master tab. This Cycle
    // Time detail is a read-only reference (canEdit forced false here).
    var canEdit = false;

    var html = '<div style="background:#fff; border:1px solid #E8E6DF; border-radius:6px; padding:12px 16px;">';
    html += '<div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:8px;">';
    html += '<span style="font-size:13px; font-weight:600; color:#333;">' + cls + ' \u2014 Classification Mappings (' + mappings.length + ')</span>';
    html += '<a href="javascript:ecnSwitchView(\'kpimaster\')" style="font-size:11px; color:#4a6fa5; text-decoration:none; font-weight:600;">Edit in KPI Master &rarr;</a>';
    html += '</div>';
    html += '<table style="width:100%; border-collapse:collapse; font-size:12px;">';
    html += '<tr style="background:#2c3e50; color:#fff;">';
    html += '<th style="padding:5px 10px; border:1px solid #444; text-align:left;">Request Classification</th>';
    html += '<th style="padding:5px 10px; border:1px solid #444; text-align:left;">Subclass</th>';
    html += '<th style="padding:5px 10px; border:1px solid #444; text-align:center; width:100px;">Std Days</th>';
    html += '<th style="padding:5px 10px; border:1px solid #444; text-align:center; width:100px;">Urg Days</th>';
    html += '</tr>';

    var idx = 0;
    var groupIdx = 0;
    window._ecnSlaGroupKeys = window._ecnSlaGroupKeys || {};
    for (var rc in byReqClass) {
        var items = byReqClass[rc];
        // Store keys for this group so "Apply to all" can find them
        var groupId = 'slaGrp' + groupIdx;
        window._ecnSlaGroupKeys[groupId] = items.map(function(m) { return m.reqClass + '|' + m.subclass; });
        html += '<tr style="background:#e9ecef;"><td colspan="' + (canEdit ? '2' : '4') + '" style="padding:4px 10px; border:1px solid #ccc; font-weight:600; color:#333;">' + rc + '</td>';
        if (canEdit) {
            html += '<td colspan="2" style="padding:4px 10px; border:1px solid #ccc; text-align:right;">' +
                '<a href="javascript:ecnApplyFirstToAll(\'' + groupId + '\')" style="font-size:11px; font-weight:normal; color:#4a6fa5; text-decoration:none;" title="Copy first row values to all rows in this group">Apply 1st to all \u25bc</a></td>';
        }
        html += '</tr>';
        groupIdx++;
        for (var j = 0; j < items.length; j++) {
            var m = items[j];
            var bg = idx % 2 === 0 ? '#fff' : '#FAFAF7';
            var tKey = m.reqClass + '|' + m.subclass;
            var isDirty = ecnState.dirtyTargets[tKey];
            var cellHighlight = isDirty ? 'background:#fff3cd;' : '';
            // Use dirty values if they exist
            var dispStd = (isDirty && isDirty.standard !== undefined) ? isDirty.standard : m.standard;
            var dispUrg = (isDirty && isDirty.urgent !== undefined) ? isDirty.urgent : m.urgent;

            html += '<tr style="background:' + bg + ';">';
            html += '<td style="padding:3px 10px; border:1px solid #eee;"></td>';
            html += '<td style="padding:3px 10px; border:1px solid #eee;">' + m.subclass + '</td>';

            // Standard - editable input
            var stdIsNA = (dispStd === 'NA' || dispStd === 'TBD' || dispStd === null || dispStd === undefined);
            html += '<td style="padding:3px 10px; border:1px solid #eee; text-align:center; ' + cellHighlight + '">';
            if (canEdit) {
                html += '<input type="number" value="' + (stdIsNA ? '' : dispStd) + '" placeholder="' + (stdIsNA ? 'NA' : '') + '" ' +
                    'style="width:60px; text-align:center; border:1px solid #ddd; border-radius:3px; padding:2px;' + (stdIsNA ? ' color:#999;' : '') + '" ' +
                    'onchange="ecnEditTarget(\'' + tKey.replace(/'/g, "\\'") + '\', \'standard\', this.value)">';
            } else {
                html += stdIsNA ? '<span style="color:#6B7280;">NA</span>' : '<strong>' + dispStd + '</strong>';
            }
            html += '</td>';

            // Urgent - editable input
            var urgIsNA = (dispUrg === 'NA' || dispUrg === 'TBD' || dispUrg === null || dispUrg === undefined);
            html += '<td style="padding:3px 10px; border:1px solid #eee; text-align:center; ' + cellHighlight + '">';
            if (canEdit) {
                html += '<input type="number" value="' + (urgIsNA ? '' : dispUrg) + '" placeholder="' + (urgIsNA ? 'NA' : '') + '" ' +
                    'style="width:60px; text-align:center; border:1px solid #ddd; border-radius:3px; padding:2px;' + (urgIsNA ? ' color:#999;' : '') + '" ' +
                    'onchange="ecnEditTarget(\'' + tKey.replace(/'/g, "\\'") + '\', \'urgent\', this.value)">';
            } else {
                html += urgIsNA ? '<span style="color:#6B7280;">NA</span>' : '<strong>' + dispUrg + '</strong>';
            }
            html += '</td>';

            html += '</tr>';
            idx++;
        }
    }
    html += '</table>';
    if (canEdit) {
        html += '<div style="margin-top:10px; display:flex; gap:8px;">';
        html += '<button class="tool-btn" onclick="ecnSaveProfileModal()">Save as Profile...</button>';
        html += '<button class="tool-btn" onclick="ecnResetTargets()">Reset to Baseline</button>';
        if (ecnState.canEdit) {
            html += '<button class="tool-btn" onclick="ecnPromoteProfile()" style="color:#856404;">\u2605 Promote to Active SLA</button>';
        }
        html += '</div>';
    }
    html += '</div>';

    detailEl.innerHTML = html;
    detailEl.style.display = '';
}

// ── POM Panel ────────────────────────────────────────────────────────

function ecnRenderPomPanel(pom) {
    if (pom.length === 0) {
        document.getElementById('ecnPomContent').innerHTML = '<p style="color:#6B7280; padding:10px;">No monthly data available.</p>';
        return;
    }
    // 3 month tiles in a row with delta vs prior month — per design audit v3
    var lastIdx = pom.length - 1;
    var html = '<div style="display:grid; grid-template-columns:repeat(' + pom.length + ', 1fr); gap:0; border:1px solid #E8E6DF; border-radius:6px; overflow:hidden; background:#fff;">';
    for (var i = 0; i < pom.length; i++) {
        var m = pom[i];
        var prev = i > 0 ? pom[i - 1] : null;
        var deltaPct = (prev && typeof m.pctOnTarget === 'number' && typeof prev.pctOnTarget === 'number')
            ? Math.round((m.pctOnTarget - prev.pctOnTarget) * 10) / 10 : null;
        var sep = i < lastIdx ? 'border-right:1px solid #E8E6DF;' : '';
        var pctColor = ecnPctColor(m.pctOnTarget);
        var monthLabel = m.label + (i === lastIdx ? ' \u00b7 current' : '');

        html += '<div style="padding:14px 18px; ' + sep + ' display:flex; justify-content:space-between; align-items:center; gap:8px;">';
        // Left side: month + count + days
        html += '<div>';
        html += '<div style="font-family:monospace; font-size:10px; color:#6B7280; letter-spacing:0.05em; text-transform:uppercase;">' + monthLabel + '</div>';
        html += '<div style="font-family:monospace; font-size:18px; font-weight:600; line-height:1.1; margin-top:3px;">' + m.total +
            ' <span style="font-size:11px; color:#6B7280; font-weight:400;">ECNs \u00b7 ' + m.avgDays + ' days</span></div>';
        html += '</div>';
        // Right side: % on target + delta
        html += '<div style="text-align:right;">';
        html += '<div style="font-family:monospace; font-size:18px; font-weight:600; line-height:1.1; color:' + pctColor + ';">' +
            (m.pctOnTarget !== null ? m.pctOnTarget + '%' : 'NA') + '</div>';
        html += '<div style="font-size:10px; color:#9CA3AF; margin-top:2px;">on target</div>';
        if (deltaPct !== null) {
            var deltaColor = deltaPct >= 0 ? '#1F8A4C' : '#B8342B';
            var deltaArrow = deltaPct >= 0 ? '\u2191' : '\u2193';
            html += '<div style="font-family:monospace; font-size:10px; margin-top:3px; color:' + deltaColor + ';">' +
                deltaArrow + ' ' + Math.abs(deltaPct) + ' pts</div>';
        }
        html += '</div></div>';
    }
    html += '</div>';
    document.getElementById('ecnPomContent').innerHTML = html;
}

// ── Chart.js Helpers ─────────────────────────────────────────────────

function ecnDestroyChart(canvasId) {
    if (ecnState.chartInstances[canvasId]) {
        ecnState.chartInstances[canvasId].destroy();
        delete ecnState.chartInstances[canvasId];
    }
}

// Returns true if a numeric series shows meaningful variation worth charting.
// `pctRangeThreshold` is the minimum (max-min)/max ratio for relative metrics;
// `absRangeThreshold` is a minimum absolute spread (units depend on series).
function ecnSeriesIsInteresting(series, pctRangeThreshold, absRangeThreshold) {
    var nums = series.filter(function(v) { return typeof v === 'number' && !isNaN(v); });
    if (nums.length < 2) return false;
    var min = Math.min.apply(null, nums);
    var max = Math.max.apply(null, nums);
    var range = max - min;
    // Both thresholds must be exceeded — pct OR abs check is enough on its own,
    // but a 0/falsy threshold means "skip this check" (not "always pass").
    if (absRangeThreshold && range >= absRangeThreshold) return true;
    if (pctRangeThreshold && max > 0 && (range / max) >= pctRangeThreshold) return true;
    return false;
}

function ecnRenderMinimizedChart(canvasId, label) {
    var wrap = document.getElementById(canvasId + 'Wrap');
    if (!wrap) return;
    wrap.innerHTML = '<div style="display:flex; justify-content:space-between; align-items:center; padding:10px 14px; background:#FAFAF7; border-top:1px solid #E8E6DF; font-size:11px;">' +
        '<span style="color:#6B7280;">' + label + ' \u2014 minimal variation</span>' +
        '<a href="javascript:ecnExpandChart(\'' + canvasId + '\')" style="color:#4a6fa5; text-decoration:none; font-weight:500;">View trend \u25be</a>' +
        '</div>';
}

function ecnRestoreChartCanvas(canvasId, showHideLink) {
    var wrap = document.getElementById(canvasId + 'Wrap');
    if (!wrap) return;
    var hideHtml = showHideLink
        ? '<div style="text-align:right; padding:6px 10px 4px; font-size:11px;">' +
          '<a href="javascript:ecnHideChart(\'' + canvasId + '\')" style="color:#4a6fa5; text-decoration:none;">Hide \u25b4</a>' +
          '</div>'
        : '';
    wrap.innerHTML = '<div style="position:relative; height:140px; width:100%;"><canvas id="' + canvasId + '"></canvas></div>' + hideHtml;
}

function ecnExpandChart(canvasId) {
    ecnState.expandedCharts[canvasId] = true;
    ecnRefreshUI();
}

function ecnHideChart(canvasId) {
    delete ecnState.expandedCharts[canvasId];
    ecnRefreshUI();
}

function ecnDrawVolumeChart(canvasId, trends) {
    ecnDestroyChart(canvasId);
    var months = trends.months;
    var labels = [], stdData = [], urgData = [], totalData = [];
    for (var i = 0; i < months.length; i++) {
        labels.push(months[i].label);
        var d = trends.data[months[i].key] || {};
        stdData.push(d.standard || 0);
        urgData.push(d.urgent || 0);
        totalData.push((d.standard || 0) + (d.urgent || 0));
    }
    // Significance: monthly volume swings ≥30% relatively AND absolute ≥100
    var interesting = ecnSeriesIsInteresting(totalData, 0.30, 100);
    var manuallyExpanded = !!ecnState.expandedCharts[canvasId];
    if (!interesting && !manuallyExpanded) {
        ecnRenderMinimizedChart(canvasId, 'Monthly volume trend');
        return;
    }
    ecnRestoreChartCanvas(canvasId, manuallyExpanded);
    var canvas = document.getElementById(canvasId);
    if (canvas && typeof Chart !== 'undefined') {
        // PT-76: Jimmy asked to put Standard and Urgent side-by-side instead of
        // stacked, so the volume of each priority shows independently rather than
        // contributing to a single total.
        ecnState.chartInstances[canvasId] = new Chart(canvas, {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [
                    { label: 'Standard', data: stdData, backgroundColor: '#4a6fa5', borderRadius: 3 },
                    { label: 'Urgent', data: urgData, backgroundColor: '#B8342B', borderRadius: 3 }
                ]
            },
            options: {
                responsive: true, maintainAspectRatio: false,
                plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 11 } } } },
                scales: {
                    x: { grid: { display: false } },
                    y: { beginAtZero: true, ticks: { font: { size: 11 } } }
                }
            }
        });
    }
    // PT-77: add a quarterly trend toggle below the monthly chart. Last 3
    // completed quarters, std/urg grouped. Stays meaningful in early January
    // when YTD is near-empty.
    ecnAppendQuarterlyToggle(canvasId, 'volume');
}

function ecnDrawCycleTimeChart(canvasId, trends) {
    ecnDestroyChart(canvasId);
    var months = trends.months;
    var labels = [], stdPctData = [], urgPctData = [], overallPctData = [];
    for (var i = 0; i < months.length; i++) {
        labels.push(months[i].label);
        var d = trends.data[months[i].key] || {};
        // PT-77: chart now tracks % on Target instead of Avg Days. null →
        // bar renders as 0; user can read the actual NA in the table.
        stdPctData.push(d.stdPctOnTarget == null ? 0 : d.stdPctOnTarget);
        urgPctData.push(d.urgPctOnTarget == null ? 0 : d.urgPctOnTarget);
        overallPctData.push(d.pctOnTarget == null ? 0 : d.pctOnTarget);
    }
    // Significance: % on Target is bounded 0–100 so a 10-point swing matters.
    var interesting = ecnSeriesIsInteresting(overallPctData, 0.15, 10);
    var manuallyExpanded = !!ecnState.expandedCharts[canvasId];
    if (!interesting && !manuallyExpanded) {
        ecnRenderMinimizedChart(canvasId, 'Cycle time trend');
        return;
    }
    // PT-77: cycle-time chart now tracks % on Target (Jimmy's spec) instead of
    // Avg Cycle Days. Y-axis pinned to 0–100; the 100% line is the target.
    // Same grouped Std/Urg bars as before.
    ecnRestoreChartCanvas(canvasId, manuallyExpanded);
    var canvas = document.getElementById(canvasId);
    if (canvas && typeof Chart !== 'undefined') {
        ecnState.chartInstances[canvasId] = new Chart(canvas, {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [
                    { label: 'Standard', data: stdPctData, backgroundColor: '#4a6fa5', borderRadius: 3 },
                    { label: 'Urgent', data: urgPctData, backgroundColor: '#B8342B', borderRadius: 3 }
                ]
            },
            options: {
                responsive: true, maintainAspectRatio: false,
                plugins: {
                    legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 11 } } },
                    tooltip: { callbacks: {
                        label: function(ctx) { return ctx.dataset.label + ': ' + ctx.parsed.y + '%'; }
                    } }
                },
                scales: {
                    x: { grid: { display: false }, ticks: { font: { size: 11 } } },
                    y: { beginAtZero: true, max: 100, ticks: {
                            font: { size: 11 },
                            callback: function(v) { return v + '%'; }
                         },
                         title: { display: true, text: '% on Target', font: { size: 11 } } }
                }
            }
        });
    }
    // PT-74/77: append a "View quarterly trend ▾" link + a hidden quarterly canvas.
    // Toggle is per-canvas so each panel (Std / PDR / Dedicated) tracks its own
    // open state. The Team panel uses its own toggle now (ecnDrawTeamVolumeChart).
    ecnAppendQuarterlyToggle(canvasId, 'cycleTimePct');
}

// PT-74: 3 side-by-side bars showing per-quarter Std/Urg metrics.
// PT-77: now serves 3 chart kinds — 'cycleTimePct' (% on Target, Y-axis 0–100%),
// 'volume' (ECN counts), and 'teamVolume' (one bar per team, top 6 + Others).
// Quarter window is last-3-completed (excludes the in-progress quarter).
function ecnDrawQuarterlyChart(canvasId, kind) {
    ecnDestroyChart(canvasId);
    var canvas = document.getElementById(canvasId);
    if (!canvas || typeof Chart === 'undefined') return;

    if (kind === 'teamVolume') {
        var tq = ecnState.teamQuarterly;
        if (!tq) return;
        var qLabels = tq.quarters.map(function(q) { return q.label; });
        var datasets = [];
        for (var ti = 0; ti < tq.teams.length; ti++) {
            var team = tq.teams[ti];
            var row = [];
            for (var qi = 0; qi < tq.quarters.length; qi++) {
                row.push(tq.data[team][tq.quarters[qi].key] || 0);
            }
            datasets.push({
                label: team,
                data: row,
                backgroundColor: ECN_TEAM_COLORS[ti % ECN_TEAM_COLORS.length],
                borderRadius: 2
            });
        }
        ecnState.chartInstances[canvasId] = new Chart(canvas, {
            type: 'bar',
            data: { labels: qLabels, datasets: datasets },
            options: {
                responsive: true, maintainAspectRatio: false,
                plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 10 } } } },
                scales: {
                    x: { grid: { display: false }, ticks: { font: { size: 11 } } },
                    y: { beginAtZero: true, ticks: { font: { size: 11 } },
                         title: { display: true, text: 'ECNs completed', font: { size: 11 } } }
                }
            }
        });
        return;
    }

    var qTrends = ecnState.quarterlyTrends;
    if (!qTrends) return;
    var labels = [], stdData = [], urgData = [];
    var qtrs = qTrends.quarters;
    for (var i = 0; i < qtrs.length; i++) {
        labels.push(qtrs[i].label);
        var d = qTrends.data[qtrs[i].key] || {};
        if (kind === 'volume') {
            stdData.push(d.stdTotal || 0);
            urgData.push(d.urgTotal || 0);
        } else {  // 'cycleTimePct'
            stdData.push(d.stdPctOnTarget == null ? 0 : d.stdPctOnTarget);
            urgData.push(d.urgPctOnTarget == null ? 0 : d.urgPctOnTarget);
        }
    }
    var isPct = (kind === 'cycleTimePct');
    var yTitle = isPct ? '% on Target' : 'ECNs completed';
    ecnState.chartInstances[canvasId] = new Chart(canvas, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [
                { label: 'Standard', data: stdData, backgroundColor: '#4a6fa5', borderRadius: 3 },
                { label: 'Urgent', data: urgData, backgroundColor: '#B8342B', borderRadius: 3 }
            ]
        },
        options: {
            responsive: true, maintainAspectRatio: false,
            plugins: {
                legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 11 } } },
                tooltip: isPct ? { callbacks: {
                    label: function(ctx) { return ctx.dataset.label + ': ' + ctx.parsed.y + '%'; }
                } } : {}
            },
            scales: {
                x: { grid: { display: false }, ticks: { font: { size: 11 } } },
                y: {
                    beginAtZero: true,
                    max: isPct ? 100 : undefined,
                    ticks: {
                        font: { size: 11 },
                        callback: function(v) { return isPct ? v + '%' : v; }
                    },
                    title: { display: true, text: yTitle, font: { size: 11 } }
                }
            }
        }
    });
}

// PT-74/77: writes a "View quarterly trend ▾" link + collapsible canvas into the
// existing chart wrap. `kind` selects which series to render — see
// ecnDrawQuarterlyChart for the dispatch table. Idempotent: the wrap div is
// re-built by ecnRestoreChartCanvas each render, so we append fresh markup
// every time and re-draw the quarterly chart if it was previously expanded.
function ecnAppendQuarterlyToggle(canvasId, kind) {
    var wrap = document.getElementById(canvasId + 'Wrap');
    if (!wrap) return;
    var qCanvasId = canvasId + 'Quarterly';
    var open = !!ecnState.expandedQuarterly[canvasId];
    var caption;
    if (kind === 'volume')          caption = 'Volume by priority &mdash; last 3 completed quarters';
    else if (kind === 'teamVolume') caption = 'Volume by team &mdash; last 3 completed quarters';
    else                            caption = '% on Target by priority &mdash; last 3 completed quarters';
    var togglerHtml = '<div style="border-top:1px solid #E8E6DF; padding:6px 10px; text-align:right; font-size:11px;">' +
        '<a href="javascript:ecnToggleQuarterlyChart(\'' + canvasId + '\',\'' + kind + '\')" style="color:#4a6fa5; text-decoration:none; font-weight:500;">' +
        'View quarterly trend ' + (open ? '▴' : '▾') + '</a></div>';
    var canvasWrapHtml = '<div id="' + qCanvasId + 'Wrap" style="' + (open ? '' : 'display:none;') + '">' +
        '<div style="font-size:11px; color:#6B7280; padding:4px 10px 0;">' + caption + '</div>' +
        '<div style="position:relative; height:140px; width:100%;"><canvas id="' + qCanvasId + '"></canvas></div></div>';
    wrap.insertAdjacentHTML('beforeend', togglerHtml + canvasWrapHtml);
    // Stash the kind on the canvas so the toggle handler knows what to redraw.
    ecnState.expandedQuarterly.__kinds = ecnState.expandedQuarterly.__kinds || {};
    ecnState.expandedQuarterly.__kinds[canvasId] = kind;
    if (open) {
        ecnDrawQuarterlyChart(qCanvasId, kind);
    }
}

function ecnToggleQuarterlyChart(canvasId, kind) {
    if (ecnState.expandedQuarterly[canvasId]) {
        delete ecnState.expandedQuarterly[canvasId];
        ecnDestroyChart(canvasId + 'Quarterly');
    } else {
        ecnState.expandedQuarterly[canvasId] = true;
    }
    ecnRefreshUI();
}

// PT-77: ECN Volume by team — grouped monthly bars, one bar per team
// (top 6 by total volume + "Others" rollup). Replaces the cycle-time chart
// on the Product Team panel per Jimmy's spec.
function ecnDrawTeamVolumeChart(canvasId, teamMonthly) {
    ecnDestroyChart(canvasId);
    if (!teamMonthly) return;
    var months = teamMonthly.months;
    var labels = months.map(function(m) { return m.label; });
    var datasets = [];
    var totals = [];  // significance gate (sum across teams per month)
    for (var mi = 0; mi < months.length; mi++) {
        var tot = 0;
        for (var tj = 0; tj < teamMonthly.teams.length; tj++) {
            tot += teamMonthly.data[teamMonthly.teams[tj]][months[mi].key] || 0;
        }
        totals.push(tot);
    }
    var interesting = ecnSeriesIsInteresting(totals, 0.30, 50);
    var manuallyExpanded = !!ecnState.expandedCharts[canvasId];
    if (!interesting && !manuallyExpanded) {
        ecnRenderMinimizedChart(canvasId, 'Monthly volume by team');
        return;
    }
    ecnRestoreChartCanvas(canvasId, manuallyExpanded);
    var canvas = document.getElementById(canvasId);
    if (canvas && typeof Chart !== 'undefined') {
        for (var ti = 0; ti < teamMonthly.teams.length; ti++) {
            var team = teamMonthly.teams[ti];
            var row = [];
            for (var ki = 0; ki < months.length; ki++) {
                row.push(teamMonthly.data[team][months[ki].key] || 0);
            }
            datasets.push({
                label: team,
                data: row,
                backgroundColor: ECN_TEAM_COLORS[ti % ECN_TEAM_COLORS.length],
                borderRadius: 2
            });
        }
        ecnState.chartInstances[canvasId] = new Chart(canvas, {
            type: 'bar',
            data: { labels: labels, datasets: datasets },
            options: {
                responsive: true, maintainAspectRatio: false,
                plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 10 } } } },
                scales: {
                    x: { grid: { display: false }, ticks: { font: { size: 11 } } },
                    y: { beginAtZero: true, ticks: { font: { size: 11 } },
                         title: { display: true, text: 'ECNs completed', font: { size: 11 } } }
                }
            }
        });
    }
    ecnAppendQuarterlyToggle(canvasId, 'teamVolume');
}

// ── Targets Panel ────────────────────────────────────────────────────

var ecnTargetsExpanded = false;

function ecnToggleTargets() {
    ecnTargetsExpanded = !ecnTargetsExpanded;
    document.getElementById('ecnTargetsBody').style.display = ecnTargetsExpanded ? '' : 'none';
    var arrow = ecnTargetsExpanded ? '\u25b2' : '\u25bc';
    var overrideCount = Object.keys(ecnState.dirtyTargets).length;
    var badge = overrideCount > 0 ? ' (' + overrideCount + ' unsaved changes)' : '';
    document.querySelector('#ecnTargetsGroup .group-head h3').innerHTML = arrow + ' SLA Targets' +
        '<span style="font-size:11px; color:#C7801B; font-weight:normal;">' + badge + '</span>';
}

function ecnRenderTargetsTable() {
    var targets = ecnState.currentTargets;
    var dirty = ecnState.dirtyTargets;
    var groups = {};
    for (var key in targets) {
        var parts = key.split('|');
        var cls = parts[0] || 'Unknown';
        var sub = parts[1] || '';
        if (!groups[cls]) groups[cls] = [];
        groups[cls].push({ key: key, subclass: sub, standard: targets[key].standard, urgent: targets[key].urgent });
    }
    var html = '<table style="width:100%; border-collapse:collapse; font-size:13px;">';
    html += '<tr style="background:#2c3e50; color:#fff;">';
    html += '<th style="padding:6px 10px; border:1px solid #444; text-align:left;">Request Classification</th>';
    html += '<th style="padding:6px 10px; border:1px solid #444; text-align:left;">Subclass</th>';
    html += '<th style="padding:6px 10px; border:1px solid #444; text-align:center; width:100px;">Target Std</th>';
    html += '<th style="padding:6px 10px; border:1px solid #444; text-align:center; width:100px;">Target Urgent</th>';
    html += '</tr>';
    var idx = 0;
    for (var cls in groups) {
        html += '<tr style="background:#e9ecef;"><td colspan="4" style="padding:6px 10px; border:1px solid #ccc; font-weight:600;">' + cls + '</td></tr>';
        var items = groups[cls];
        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            var bg = idx % 2 === 0 ? '#fff' : '#FAFAF7';
            var isOverridden = dirty[item.key] || (ecnState.activeProfile && ecnState.activeProfile.overrides && ecnState.activeProfile.overrides[item.key]);
            var cellStyle = isOverridden ? 'background:#fff3cd;' : '';
            html += '<tr style="background:' + bg + ';">';
            html += '<td style="padding:4px 10px; border:1px solid #ccc;"></td>';
            html += '<td style="padding:4px 10px; border:1px solid #ccc;">' + item.subclass + '</td>';
            // Standard
            var stdVal = item.standard;
            var stdIsNA = (stdVal === 'NA' || stdVal === 'TBD' || stdVal === null || stdVal === undefined);
            html += '<td style="padding:4px 10px; border:1px solid #ccc; text-align:center; ' + cellStyle + '">' +
                '<input type="number" value="' + (stdIsNA ? '' : stdVal) + '" placeholder="' + (stdIsNA ? 'NA' : '') + '" ' +
                'style="width:60px; text-align:center; border:1px solid ' + (stdIsNA ? '#eee' : '#ddd') + '; border-radius:3px; padding:2px;' + (stdIsNA ? ' color:#999;' : '') + '" ' +
                'onchange="ecnEditTarget(\'' + item.key.replace(/'/g, "\\'") + '\', \'standard\', this.value)">' +
                '</td>';
            // Urgent
            var urgVal = item.urgent;
            var urgIsNA = (urgVal === 'NA' || urgVal === 'TBD' || urgVal === null || urgVal === undefined);
            html += '<td style="padding:4px 10px; border:1px solid #ccc; text-align:center; ' + cellStyle + '">' +
                '<input type="number" value="' + (urgIsNA ? '' : urgVal) + '" placeholder="' + (urgIsNA ? 'NA' : '') + '" ' +
                'style="width:60px; text-align:center; border:1px solid ' + (urgIsNA ? '#eee' : '#ddd') + '; border-radius:3px; padding:2px;' + (urgIsNA ? ' color:#999;' : '') + '" ' +
                'onchange="ecnEditTarget(\'' + item.key.replace(/'/g, "\\'") + '\', \'urgent\', this.value)">' +
                '</td>';
            html += '</tr>';
            idx++;
        }
    }
    html += '</table>';
    var overrideCount = Object.keys(dirty).length;
    if (overrideCount > 0) {
        html += '<div style="margin-top:8px; padding:8px 12px; background:#fff3cd; border-left:4px solid #C7801B; border-radius:0 6px 6px 0; font-size:13px;">' +
            overrideCount + ' unsaved target change(s). ' +
            '<a href="javascript:ecnSaveProfileModal()" style="color:#4a6fa5;">Save as Profile</a> or ' +
            '<a href="javascript:ecnSaveAndRecalculate()" style="color:#4a6fa5;">Save & Recalculate</a></div>';
    }
    document.getElementById('ecnTargetsTable').innerHTML = html;
}

function ecnEditTarget(key, priority, value) {
    var numVal = parseFloat(value);
    if (isNaN(numVal)) return;
    if (!ecnState.dirtyTargets[key]) ecnState.dirtyTargets[key] = {};
    ecnState.dirtyTargets[key][priority] = numVal;
    // Don't refresh — just update the dirty count badge and show the apply bar
    ecnUpdateDirtyBadge();
}

function ecnApplyTargetEdits() {
    ecnState.currentTargets = ecnMergeTargets(ecnState.baselineTargets, ecnState.activeProfile);
    ecnState.appliedDirtyCount = Object.keys(ecnState.dirtyTargets).length;
    ecnRefreshUI();
}

function ecnUpdateDirtyBadge() {
    var pendingCount = 0;
    // Count only truly new edits that haven't been applied yet
    // (after apply, appliedDirtyCount matches dirtyTargets length)
    var totalDirty = Object.keys(ecnState.dirtyTargets).length;
    var applied = ecnState.appliedDirtyCount || 0;
    pendingCount = totalDirty - applied;
    if (pendingCount < 0) pendingCount = 0;

    // Update the profile label
    var profileEl = document.getElementById('ecnSlaProfileLabel');
    if (profileEl) {
        var profileLabel = 'Baseline (Excel Template)';
        if (ecnState.activeProfile) {
            profileLabel = ecnState.activeProfile.name + (ecnState.activeProfile.active ? ' \u2605' : '');
        }
        if (totalDirty > 0) profileLabel += ' (+' + totalDirty + ' edits)';
        profileEl.innerHTML = 'Profile: <strong>' + profileLabel + '</strong>';
    }
    // Show/hide the apply bar
    var applyBar = document.getElementById('ecnApplyBar');
    if (applyBar) {
        if (pendingCount > 0) {
            // New pending edits — show apply + discard
            applyBar.style.display = '';
            applyBar.innerHTML = '<div style="display:flex; align-items:center; justify-content:space-between;">' +
                '<span style="font-size:13px; color:#856404;"><strong>' + pendingCount + '</strong> new target edit(s) pending</span>' +
                '<div style="display:flex; gap:8px;">' +
                '<button class="tool-btn" onclick="ecnApplyTargetEdits()" style="background:#4a6fa5; color:#fff; font-weight:600;">Apply & Recalculate</button>' +
                '<button class="tool-btn" onclick="ecnResetTargets()">Discard All</button>' +
                '</div></div>';
        } else if (applied > 0) {
            // Edits have been applied — show revert option
            applyBar.style.display = '';
            applyBar.innerHTML = '<div style="display:flex; align-items:center; justify-content:space-between;">' +
                '<span style="font-size:13px; color:#856404;">' + applied + ' target edit(s) applied to dashboard</span>' +
                '<div style="display:flex; gap:8px;">' +
                '<button class="tool-btn" onclick="ecnSaveProfileModal()">Save as Profile...</button>' +
                '<button class="tool-btn" onclick="ecnResetTargets()">Revert to Baseline</button>' +
                '</div></div>';
        } else {
            applyBar.style.display = 'none';
        }
    }
}

function ecnApplyFirstToAll(groupId) {
    var keys = window._ecnSlaGroupKeys[groupId];
    if (!keys || keys.length < 2) return;
    // Get the first row's current values (from dirty or current targets)
    var firstKey = keys[0];
    var firstDirty = ecnState.dirtyTargets[firstKey];
    var firstCurrent = ecnState.currentTargets[firstKey] || {};
    var std = (firstDirty && firstDirty.standard !== undefined) ? firstDirty.standard : firstCurrent.standard;
    var urg = (firstDirty && firstDirty.urgent !== undefined) ? firstDirty.urgent : firstCurrent.urgent;
    if ((std === undefined || std === null || std === 'NA') && (urg === undefined || urg === null || urg === 'NA')) {
        appAlert('Set values in the first row before applying to all.');
        return;
    }
    // Apply to all rows in the group
    for (var i = 1; i < keys.length; i++) {
        if (!ecnState.dirtyTargets[keys[i]]) ecnState.dirtyTargets[keys[i]] = {};
        if (std !== undefined && std !== null && std !== 'NA') ecnState.dirtyTargets[keys[i]].standard = std;
        if (urg !== undefined && urg !== null && urg !== 'NA') ecnState.dirtyTargets[keys[i]].urgent = urg;
    }
    // Also mark first row as dirty if it came from an input
    if (firstDirty) {
        // already dirty
    } else {
        if (!ecnState.dirtyTargets[firstKey]) ecnState.dirtyTargets[firstKey] = {};
        if (std !== undefined && std !== null && std !== 'NA') ecnState.dirtyTargets[firstKey].standard = std;
        if (urg !== undefined && urg !== null && urg !== 'NA') ecnState.dirtyTargets[firstKey].urgent = urg;
    }
    ecnUpdateDirtyBadge();
    // Re-render the detail panel to show updated inputs
    var openCls = _ecnSlaDetailOpen;
    if (openCls) { _ecnSlaDetailOpen = null; ecnToggleSlaDetail(openCls); }
}

function ecnResetTargets() {
    ecnState.dirtyTargets = {};
    ecnState.appliedDirtyCount = 0;
    ecnState.currentTargets = ecnMergeTargets(ecnState.baselineTargets, ecnState.activeProfile);
    ecnRefreshUI();
}

function ecnSaveAndRecalculate() {
    if (!ecnState.activeProfile) { ecnSaveProfileModal(); return; }
    var overrides = ecnState.activeProfile.overrides || {};
    for (var key in ecnState.dirtyTargets) {
        if (!overrides[key]) overrides[key] = {};
        for (var p in ecnState.dirtyTargets[key]) overrides[key][p] = ecnState.dirtyTargets[key][p];
    }
    fetch('/api/ecn-report/recalculate', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profileId: ecnState.activeProfile.id, overrides: overrides })
    }).then(function(r) { return r.json(); }).then(function(data) {
        if (data.success) {
            ecnState.activeProfile.overrides = overrides;
            ecnState.dirtyTargets = {};
            ecnRefreshUI();
        } else { appAlert(data.message || 'Recalculation failed.'); }
    }).catch(function(err) { appAlert('Error: ' + err.message); });
}

// ── Data Table ───────────────────────────────────────────────────────

function ecnRenderDeltaPills(rows) {
    var pillsEl = document.getElementById('ecnDeltaPills');
    if (!pillsEl) return;

    // Compute counts by bucket from the FULL rows (before any filtering)
    var counts = { early: 0, late1_5: 0, late6_10: 0, late10p: 0, na: 0 };
    for (var i = 0; i < rows.length; i++) {
        var d = rows[i]._dayDiff;
        if (d === null || d === undefined) counts.na++;
        else if (d <= 0) counts.early++;
        else if (d <= 5) counts.late1_5++;
        else if (d <= 10) counts.late6_10++;
        else counts.late10p++;
    }

    var df = ecnState.deltaFilter;
    function isActive(name) {
        if (!df) return name === 'all';
        if (df.name === name) return true;
        return false;
    }
    function pillStyle(bg, color, border, active) {
        var base = 'display:inline-block; padding:6px 12px; margin:0 6px 0 0; border-radius:14px; font-size:12px; font-weight:600; cursor:pointer; user-select:none; ';
        if (active) {
            return base + 'background:' + color + '; color:#fff; border:1px solid ' + color + ';';
        }
        return base + 'background:' + bg + '; color:' + color + '; border:1px solid ' + border + ';';
    }

    var html = '<span style="font-size:11px; color:#6B7280; font-weight:500; margin-right:8px;">QUICK FILTER:</span>';
    html += '<span style="' + pillStyle('#f3f4f6', '#374151', '#d1d5db', isActive('all')) + '" onclick="ecnSetDeltaFilter(null)">All <span style="opacity:0.6;">(' + rows.length + ')</span></span>';
    html += '<span style="' + pillStyle('#e8f5e9', '#1F8A4C', '#a5d6a7', isActive('early')) + '" onclick="ecnSetDeltaFilter(\'early\')">On time / Early <span style="opacity:0.6;">(' + counts.early + ')</span></span>';
    html += '<span style="' + pillStyle('#fff8e1', '#C7801B', '#ffe0a3', isActive('late1_5')) + '" onclick="ecnSetDeltaFilter(\'late1_5\')">1\u20135 days late <span style="opacity:0.6;">(' + counts.late1_5 + ')</span></span>';
    html += '<span style="' + pillStyle('#fff3cd', '#d97706', '#ffd180', isActive('late6_10')) + '" onclick="ecnSetDeltaFilter(\'late6_10\')">6\u201310 days late <span style="opacity:0.6;">(' + counts.late6_10 + ')</span></span>';
    html += '<span style="' + pillStyle('#fdeaea', '#B8342B', '#f4b3b3', isActive('late10p')) + '" onclick="ecnSetDeltaFilter(\'late10p\')">10+ days late <span style="opacity:0.6;">(' + counts.late10p + ')</span></span>';
    html += '<span style="' + pillStyle('#f3f4f6', '#6B7280', '#d1d5db', isActive('na')) + '" onclick="ecnSetDeltaFilter(\'na\')">Untracked <span style="opacity:0.6;">(' + counts.na + ')</span></span>';

    pillsEl.innerHTML = html;
    pillsEl.style.display = '';
}

function ecnRenderInlineSla() {
    var el = document.getElementById('ecnDataSlaInline');
    if (!el) return;
    // Find the dominant Standard ECN target (most common standard/urgent pair for "Standard ECNs" classification)
    var targets = ecnState.currentTargets || {};
    var baseline = ecnState.baselineTargets || {};
    var pairs = {};
    for (var key in targets) {
        var be = baseline[key];
        var cls = be ? be.ecnClassification : '';
        if (cls !== 'Standard ECNs' && cls !== 'General ECNs') continue;
        var t = targets[key];
        if (t.standard === 'NA' || t.urgent === 'NA') continue;
        var p = t.standard + '|' + t.urgent;
        pairs[p] = (pairs[p] || 0) + 1;
    }
    var dominant = null;
    var maxCount = 0;
    for (var p in pairs) {
        if (pairs[p] > maxCount) { maxCount = pairs[p]; dominant = p; }
    }
    if (dominant) {
        var parts = dominant.split('|');
        el.innerHTML = 'SLA (General ECNs): Standard \u2264 ' + parts[0] + ' days \u00b7 Urgent \u2264 ' + parts[1] + ' days \u00b7 ' +
            '<span style="color:#9CA3AF;">target days = networkdays(submit, completed)</span>';
    } else {
        el.innerHTML = '';
    }
}

function ecnSetDeltaFilter(name) {
    if (!name || name === 'all') {
        ecnState.deltaFilter = null;
    } else if (name === 'early') {
        ecnState.deltaFilter = { name: 'early', max: 0 };
    } else if (name === 'late1_5') {
        ecnState.deltaFilter = { name: 'late1_5', min: 1, max: 5 };
    } else if (name === 'late6_10') {
        ecnState.deltaFilter = { name: 'late6_10', min: 6, max: 10 };
    } else if (name === 'late10p') {
        ecnState.deltaFilter = { name: 'late10p', min: 11 };
    } else if (name === 'na') {
        ecnState.deltaFilter = { name: 'na', untracked: true };
    }
    ecnState.tablePage = 0;
    ecnRefreshUI();
}

// Render a user name cell as a clickable AD popup link. Reuses showUserCard()
// from app.js (same as Field Changes tab). Format: "Last, First (empId)"
function ecnRenderUserCell(userName) {
    if (!userName) return '';
    var match = userName.match(/\((\d+)\)/);
    if (!match) {
        return '<span style="color:#4a6fa5; font-weight:500;">' +
            userName.replace(/[<>]/g, '') + '</span>';
    }
    var empId = match[1];
    var displayName = userName.replace(/\s*\(\d+\)/, '').trim().replace(/[<>]/g, '');
    return '<span class="user-card-link" data-empid="' + empId +
        '" onclick="showUserCard(event, \'' + empId + '\')" ' +
        'style="color:#4a6fa5; font-weight:500; cursor:pointer; text-decoration:none; border-bottom:1px dashed #a0b8d0;" ' +
        'title="Click for directory info">' + displayName + '</span>';
}

function ecnRenderDataTable(rows) {
    // Default view: completed ECNs only (in-progress ECNs have no Actual Days)
    var statusFilteredRows = ecnState.showOnlyCompleted
        ? rows.filter(function(r) { return (r.status || '') === 'Completed'; })
        : rows;
    ecnRenderDeltaPills(statusFilteredRows);
    ecnRenderInlineSla();
    var filter = ecnState.tableFilter.toLowerCase();
    var colFilters = ecnState.columnFilters || {};
    var filtered = statusFilteredRows;
    if (filter) {
        filtered = filtered.filter(function(r) {
            return (r.number || '').toLowerCase().indexOf(filter) >= 0 ||
                   (r.requestClassification || '').toLowerCase().indexOf(filter) >= 0 ||
                   (r.requestSubclass || '').toLowerCase().indexOf(filter) >= 0 ||
                   (r.ecnClassification || '').toLowerCase().indexOf(filter) >= 0 ||
                   (r.changeType || '').toLowerCase().indexOf(filter) >= 0 ||
                   (r.productTeam || '').toLowerCase().indexOf(filter) >= 0 ||
                   (r.originator || '').toLowerCase().indexOf(filter) >= 0;
        });
    }
    // Δ Days filter pill
    if (ecnState.deltaFilter) {
        var df = ecnState.deltaFilter;
        filtered = filtered.filter(function(r) {
            if (df.untracked === true) return r._dayDiff === null || r._dayDiff === undefined;
            if (r._dayDiff === null || r._dayDiff === undefined) return false;
            var d = r._dayDiff;
            if (df.min !== undefined && d < df.min) return false;
            if (df.max !== undefined && d > df.max) return false;
            return true;
        });
    }
    // Per-column filters
    for (var cf in colFilters) {
        var v = colFilters[cf];
        if (!v || v === '(All)') continue;
        filtered = filtered.filter(function(r) {
            var rowVal = r[cf];
            if (cf === '_onTarget') {
                if (v === 'Yes') return rowVal === true;
                if (v === 'No') return rowVal === false;
                if (v === 'NA') return rowVal === null || rowVal === undefined;
            }
            return String(rowVal === null || rowVal === undefined ? '' : rowVal) === v;
        });
    }
    if (ecnState.tableSort.col !== null) {
        var col = ecnState.tableSort.col;
        var asc = ecnState.tableSort.asc;
        filtered.sort(function(a, b) {
            var va = a[col], vb = b[col];
            if (va === null || va === undefined) va = '';
            if (vb === null || vb === undefined) vb = '';
            if (typeof va === 'number' && typeof vb === 'number') return asc ? va - vb : vb - va;
            va = String(va).toLowerCase(); vb = String(vb).toLowerCase();
            return asc ? va.localeCompare(vb) : vb.localeCompare(va);
        });
    }
    var countSuffix = ecnState.showOnlyCompleted
        ? ' of ' + statusFilteredRows.length + ' completed (' + rows.length + ' total)'
        : ' of ' + rows.length;
    document.getElementById('ecnDataCount').textContent = '(' + filtered.length + countSuffix + ')';
    var toggleBtn = document.getElementById('ecnShowAllToggle');
    if (toggleBtn) {
        toggleBtn.textContent = ecnState.showOnlyCompleted ? 'Show all statuses' : 'Show completed only';
    }

    var page = ecnState.tablePage;
    var pageSize = ecnState.tablePageSize;
    var totalPages = Math.ceil(filtered.length / pageSize);
    if (page >= totalPages) page = Math.max(0, totalPages - 1);
    ecnState.tablePage = page;
    var start = page * pageSize;
    var pageRows = filtered.slice(start, start + pageSize);

    // PT-73 tooltip \u2014 surfaced on every D@<Status> column header so users hovering a
    // value see the counting rule without having to dig into release notes.
    var DAS_TIP = 'Business days only (Mon\u2013Fri, no holidays). Counts the MOST RECENT visit '
                + 'to this status \u2014 if the change looped back through this phase, only the '
                + 'last cycle is included. Hold is the exception and stays cumulative.';
    var cols = [
        { key: 'number', label: 'ECN#' }, { key: 'priority', label: 'Priority' },
        { key: 'requestClassification', label: 'Req Classification' }, { key: 'requestSubclass', label: 'Subclass' },
        { key: 'ecnClassification', label: 'ECN Classification' }, { key: 'changeType', label: 'Change Type' },
        { key: 'productTeam', label: 'Product Team' }, { key: 'actualDays', label: 'Actual Days' },
        { key: '_targetDays', label: 'Target Days' }, { key: '_dayDiff', label: '\u0394 Days' },
        { key: '_onTarget', label: 'On Target' },
        { key: 'status', label: 'Status' },
        { key: 'daysAtPending',   label: 'D@Pend',  tip: DAS_TIP },
        { key: 'daysAtSubmitted', label: 'D@Subm',  tip: DAS_TIP },
        { key: 'daysAtReview',    label: 'D@Rev',   tip: DAS_TIP },
        { key: 'daysAtRelease',   label: 'D@Rel',   tip: DAS_TIP },
        { key: 'daysAtHold',      label: 'D@Hold',  tip: 'Cumulative business days on Hold across all visits.' },
        { key: 'originator', label: 'Originator' },
        { key: 'analyst', label: 'Analyst' },
        { key: '_note', label: 'Notes' }
    ];

    // PT-73 footnote \u2014 small caption above the table so users see the work-days rule
    // without having to hover. Repeated every render (cheap; one short line).
    var html = '<div style="font-size:11px; color:#6B7280; padding:4px 0 6px 2px;">'
             + 'D@&lt;Status&gt; columns count <strong>business days only</strong> (Mon\u2013Fri). '
             + 'D@Pend/D@Subm/D@Rev/D@Rel reflect the <strong>most recent visit</strong> to each status; '
             + 'D@Hold is cumulative across all holds.'
             + '</div>';
    html += '<table style="width:100%; border-collapse:collapse; font-size:12px;">';
    html += '<tr style="background:#2c3e50; color:#fff;">';
    for (var c = 0; c < cols.length; c++) {
        var sortArrow = ecnState.tableSort.col === cols[c].key ? (ecnState.tableSort.asc ? ' \u25b2' : ' \u25bc') : '';
        var titleAttr = cols[c].tip ? ' title="' + cols[c].tip.replace(/"/g, '&quot;') + '"' : '';
        html += '<th style="padding:5px 8px; border:1px solid #444; cursor:pointer; white-space:nowrap; font-size:11px;"' + titleAttr + ' onclick="ecnSortTable(\'' + cols[c].key + '\')">' + cols[c].label + sortArrow + '</th>';
    }
    html += '</tr>';

    // Filter row — dropdown for filterable columns
    var FILTERABLE_COLS = {
        priority: true, requestClassification: true, ecnClassification: true,
        changeType: true, productTeam: true, _onTarget: true, status: true
    };
    html += '<tr style="background:#e9ecef;">';
    for (var c = 0; c < cols.length; c++) {
        var key = cols[c].key;
        if (FILTERABLE_COLS[key]) {
            var unique = {};
            if (key === '_onTarget') {
                unique['Yes'] = true; unique['No'] = true; unique['NA'] = true;
            } else {
                for (var ri = 0; ri < statusFilteredRows.length; ri++) {
                    var v = statusFilteredRows[ri][key];
                    if (v !== null && v !== undefined && v !== '') unique[String(v)] = true;
                }
            }
            var values = Object.keys(unique).sort();
            var current = (ecnState.columnFilters || {})[key] || '(All)';
            html += '<td style="padding:2px; border:1px solid #ccc;">' +
                '<select onchange="ecnSetColFilter(\'' + key + '\', this.value)" ' +
                'style="font-size:10px; width:100%; border:1px solid #ddd; border-radius:3px; padding:1px;">' +
                '<option value="(All)"' + (current === '(All)' ? ' selected' : '') + '>(All)</option>';
            for (var vi = 0; vi < values.length; vi++) {
                html += '<option value="' + values[vi].replace(/"/g, '&quot;') + '"' +
                    (current === values[vi] ? ' selected' : '') + '>' + values[vi] + '</option>';
            }
            html += '</select></td>';
        } else {
            html += '<td style="padding:2px; border:1px solid #ccc;"></td>';
        }
    }
    html += '</tr>';

    var ECN_CLASSIFICATIONS = ['Standard ECNs', "PDR ECN's", "CCB ECN's", "Dedicated Process ECN's", "Project ECN's", 'Factory Changes', 'Bulk Production Updates', 'Other ECNs'];
    var ECN_TEAMS = ['CS', 'AME', 'CSSD', 'ESSD', 'Engineering', 'Other', 'Test Equipment', 'Factory (SDSS/SM)'];

    for (var r = 0; r < pageRows.length; r++) {
        var row = pageRows[r];
        var bg = r % 2 === 0 ? '#fff' : '#FAFAF7';
        var annotation = ecnState.annotations[row.number] || {};
        html += '<tr style="background:' + bg + ';">';
        // ECN# linked to Agile PLM (same pattern as Change Reviews)
        var ecnNum = row.number || '';
        if (ecnNum) {
            var agileUrl = (window.AGILE_WEBCLIENT_URL || 'https://plm.sandisk.com/Agile') + '/object/ECN/' + encodeURIComponent(ecnNum);
            html += '<td style="padding:4px 6px; border:1px solid #ccc; white-space:nowrap;">' +
                '<a href="' + agileUrl + '" target="_blank" rel="noopener" style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;" title="Open in Agile PLM">' + ecnNum + '</a></td>';
        } else {
            html += '<td style="padding:4px 6px; border:1px solid #ccc; white-space:nowrap;"></td>';
        }
        var prColor = row.priority === 'Urgent' ? '#dc3545' : '#28a745';
        html += '<td style="padding:4px 6px; border:1px solid #ccc;"><span style="color:' + prColor + ';">\u25cf</span> ' + (row.priority || '') + '</td>';
        html += '<td style="padding:4px 6px; border:1px solid #ccc;">' + (row.requestClassification || '') + '</td>';
        html += '<td style="padding:4px 6px; border:1px solid #ccc;">' + (row.requestSubclass || '') + '</td>';
        // ECN Classification dropdown
        html += '<td style="padding:4px 6px; border:1px solid #ccc;"><select style="font-size:12px; border:1px solid #ddd; border-radius:3px; padding:1px;" onchange="ecnSaveClassOverride(\'' + row.number + '\', this.value)">';
        for (var ci = 0; ci < ECN_CLASSIFICATIONS.length; ci++) {
            html += '<option value="' + ECN_CLASSIFICATIONS[ci] + '"' + (ECN_CLASSIFICATIONS[ci] === row.ecnClassification ? ' selected' : '') + '>' + ECN_CLASSIFICATIONS[ci] + '</option>';
        }
        html += '</select></td>';
        html += '<td style="padding:4px 6px; border:1px solid #ccc;">' + (row.changeType || '') + '</td>';
        // Product Team dropdown
        html += '<td style="padding:4px 6px; border:1px solid #ccc;"><select style="font-size:12px; border:1px solid #ddd; border-radius:3px; padding:1px;" onchange="ecnSaveTeamOverride(\'' + row.number + '\', this.value)">';
        for (var ti = 0; ti < ECN_TEAMS.length; ti++) {
            html += '<option value="' + ECN_TEAMS[ti] + '"' + (ECN_TEAMS[ti] === row.productTeam ? ' selected' : '') + '>' + ECN_TEAMS[ti] + '</option>';
        }
        html += '</select></td>';
        // Actual Days = business days from Submit to Final Complete
        if (row.actualDays !== null && row.actualDays !== undefined) {
            html += '<td style="padding:4px 6px; border:1px solid #ccc; text-align:center;" ' +
                'title="NETWORKDAYS(submit, complete)">' + row.actualDays + '</td>';
        } else {
            html += '<td style="padding:4px 6px; border:1px solid #ccc; text-align:center;"></td>';
        }
        html += '<td style="padding:4px 6px; border:1px solid #ccc; text-align:center;">' + (row._targetDays !== null ? row._targetDays : 'NA') + '</td>';
        // Δ Days — color coded
        if (row._dayDiff === null || row._dayDiff === undefined) {
            html += '<td style="padding:4px 6px; border:1px solid #ccc; text-align:center; color:#6B7280;">NA</td>';
        } else {
            var d = row._dayDiff;
            var diffColor, diffBg;
            if (d <= 0) { diffColor = '#1F8A4C'; diffBg = '#e8f5e9'; }       // green: on time / early
            else if (d <= 5) { diffColor = '#C7801B'; diffBg = '#fff8e1'; }  // amber: 1-5 days late
            else if (d <= 10) { diffColor = '#d97706'; diffBg = '#fff3cd'; } // dark amber: 6-10 days late
            else { diffColor = '#B8342B'; diffBg = '#fdeaea'; }              // red: >10 days late
            // Drop +/- prefix — colored cell already conveys severity (per design audit v3)
            html += '<td style="padding:4px 6px; border:1px solid #ccc; text-align:center; font-weight:bold; color:' + diffColor + '; background:' + diffBg + ';">' + d + '</td>';
        }
        if (row._onTarget === true) html += '<td style="padding:4px 6px; border:1px solid #ccc; text-align:center; color:#1F8A4C; font-weight:bold;">\u2713</td>';
        else if (row._onTarget === false) html += '<td style="padding:4px 6px; border:1px solid #ccc; text-align:center; color:#B8342B; font-weight:bold;">\u2717</td>';
        else html += '<td style="padding:4px 6px; border:1px solid #ccc; text-align:center; color:#6B7280;">NA</td>';
        html += '<td style="padding:4px 6px; border:1px solid #ccc;">' + (row.status || '') + '</td>';
        // Days @ <Status> — only populated for Completed ECNs
        var dasKeys = ['daysAtPending','daysAtSubmitted','daysAtReview','daysAtRelease','daysAtHold'];
        for (var di = 0; di < dasKeys.length; di++) {
            var dv = row[dasKeys[di]];
            html += '<td style="padding:4px 6px; border:1px solid #ccc; text-align:center;' +
                (dv === null || dv === undefined ? ' color:#9CA3AF;' : '') + '">' +
                (dv === null || dv === undefined ? '\u2014' : dv) + '</td>';
        }
        // Originator → AD popup on click (same pattern as Field Changes "user-card-link")
        html += '<td style="padding:4px 6px; border:1px solid #ccc;">' + ecnRenderUserCell(row.originator) + '</td>';
        // Analyst → AD popup on click
        html += '<td style="padding:4px 6px; border:1px solid #ccc;">' + ecnRenderUserCell(row.analyst) + '</td>';
        var noteVal = (annotation.note || '').replace(/"/g, '&quot;');
        var noteStatusId = 'ecnNoteStatus-' + row.number.replace(/[^A-Za-z0-9]/g, '_');
        html += '<td style="padding:4px 6px; border:1px solid #ccc; white-space:nowrap;">' +
            '<input type="text" value="' + noteVal + '" ' +
            'style="width:120px; border:1px solid #eee; border-radius:3px; padding:2px 4px; font-size:12px;" ' +
            'oninput="document.getElementById(\'' + noteStatusId + '\').innerHTML = \'<span style=&quot;color:#C7801B;&quot;>\u2022 Unsaved</span>\';" ' +
            'onblur="ecnSaveNote(\'' + row.number + '\', this.value, this, \'' + noteStatusId + '\')" ' +
            'onkeydown="if(event.key===\'Enter\'){this.blur();}" ' +
            'placeholder="Add note..." title="Auto-saves on Tab/Enter or click away">' +
            '<span id="' + noteStatusId + '" style="margin-left:6px; font-size:10px; vertical-align:middle;"></span>' +
            '</td>';
        html += '</tr>';
    }
    html += '</table>';
    document.getElementById('ecnTableContainer').innerHTML = html;

    // Pagination
    var pagHtml = '';
    if (totalPages > 1) {
        pagHtml += '<button class="tool-btn" onclick="ecnGoPage(0)" ' + (page === 0 ? 'disabled' : '') + '>&laquo;</button> ';
        pagHtml += '<button class="tool-btn" onclick="ecnGoPage(' + (page - 1) + ')" ' + (page === 0 ? 'disabled' : '') + '>&lsaquo;</button> ';
        pagHtml += '<span style="font-size:13px; margin:0 10px;">Page ' + (page + 1) + ' of ' + totalPages + '</span>';
        pagHtml += '<button class="tool-btn" onclick="ecnGoPage(' + (page + 1) + ')" ' + (page >= totalPages - 1 ? 'disabled' : '') + '>&rsaquo;</button> ';
        pagHtml += '<button class="tool-btn" onclick="ecnGoPage(' + (totalPages - 1) + ')" ' + (page >= totalPages - 1 ? 'disabled' : '') + '>&raquo;</button>';
    }
    document.getElementById('ecnPagination').innerHTML = pagHtml;
}

function ecnFilterTable() {
    ecnState.tableFilter = document.getElementById('ecnTableSearch').value;
    ecnState.tablePage = 0;
    ecnRefreshUI();
}

function ecnToggleShowAll() {
    ecnState.showOnlyCompleted = !ecnState.showOnlyCompleted;
    ecnState.tablePage = 0;
    // Clear any column filters that may now match no rows in the new dataset
    ecnState.columnFilters = {};
    ecnState.deltaFilter = null;
    ecnRefreshUI();
}

function ecnSetColFilter(key, value) {
    if (!ecnState.columnFilters) ecnState.columnFilters = {};
    if (value === '(All)') {
        delete ecnState.columnFilters[key];
    } else {
        ecnState.columnFilters[key] = value;
    }
    ecnState.tablePage = 0;
    ecnRefreshUI();
}

function ecnSortTable(col) {
    if (ecnState.tableSort.col === col) ecnState.tableSort.asc = !ecnState.tableSort.asc;
    else { ecnState.tableSort.col = col; ecnState.tableSort.asc = true; }
    ecnRefreshUI();
}

function ecnGoPage(p) { ecnState.tablePage = Math.max(0, p); ecnRefreshUI(); }

function ecnSaveNote(ecnNumber, note, inputEl, statusId) {
    var statusEl = statusId ? document.getElementById(statusId) : null;
    // Skip save if value hasn't actually changed
    var existing = (ecnState.annotations[ecnNumber] && ecnState.annotations[ecnNumber].note) || '';
    if (existing === note) {
        if (statusEl) statusEl.innerHTML = '';
        return;
    }
    if (inputEl) inputEl.style.borderColor = '#C7801B';
    if (statusEl) statusEl.innerHTML = '<span style="color:#C7801B;">Saving\u2026</span>';

    fetch('/api/ecn-report/annotations', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ecnNumber: ecnNumber, note: note })
    }).then(function(r) { return r.json(); }).then(function(data) {
        if (data.success) {
            if (!ecnState.annotations[ecnNumber]) ecnState.annotations[ecnNumber] = {};
            ecnState.annotations[ecnNumber].note = note;
            if (inputEl) {
                inputEl.style.borderColor = '#1F8A4C';
                setTimeout(function() { inputEl.style.borderColor = '#eee'; }, 2500);
            }
            if (statusEl) {
                statusEl.innerHTML = '<span style="color:#1F8A4C; font-weight:600;">\u2713 Saved</span>';
                setTimeout(function() {
                    if (statusEl) statusEl.innerHTML = '';
                }, 2500);
            }
        } else {
            if (inputEl) inputEl.style.borderColor = '#B8342B';
            if (statusEl) statusEl.innerHTML = '<span style="color:#B8342B; font-weight:600;">\u2717 Failed</span>';
        }
    }).catch(function() {
        if (inputEl) inputEl.style.borderColor = '#B8342B';
        if (statusEl) statusEl.innerHTML = '<span style="color:#B8342B; font-weight:600;">\u2717 Failed</span>';
    });
}

function ecnSaveClassOverride(ecnNumber, value) {
    fetch('/api/ecn-report/annotations', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ecnNumber: ecnNumber, classificationOverride: value })
    }).then(function(r) { return r.json(); }).then(function(data) {
        if (data.success) {
            if (!ecnState.annotations[ecnNumber]) ecnState.annotations[ecnNumber] = {};
            ecnState.annotations[ecnNumber].classificationOverride = value;
            ecnRefreshUI();
        }
    }).catch(function() {});
}

function ecnSaveTeamOverride(ecnNumber, value) {
    fetch('/api/ecn-report/annotations', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ecnNumber: ecnNumber, teamOverride: value })
    }).then(function(r) { return r.json(); }).then(function(data) {
        if (data.success) {
            if (!ecnState.annotations[ecnNumber]) ecnState.annotations[ecnNumber] = {};
            ecnState.annotations[ecnNumber].teamOverride = value;
            ecnRefreshUI();
        }
    }).catch(function() {});
}

function ecnExportExcel() {
    // Server-generated XLSX has two tabs: Completed Q<n> YYYY (with formulas)
    // and In Progress Q<n> YYYY. The Completed tab opens by default.
    var a = document.createElement('a');
    a.href = '/api/ecn-report/download';
    a.download = 'ECN_Report.xlsx';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
}

// ── Run Report ───────────────────────────────────────────────────────

function ecnRunReport() {
    appConfirm('Run the ECN Dashboard? This queries Oracle and takes ~30 seconds.', { okText: 'Run' }).then(function (ok) {
        if (!ok) return;
        document.getElementById('ecnProgressWrap').style.display = '';
        document.getElementById('ecnProgressBar').style.width = '0%';
        document.getElementById('ecnProgressBar').style.background = '#4a6fa5';
        document.getElementById('ecnProgressText').textContent = 'Starting report generation...';
        fetch('/api/ecn-report/run', { method: 'POST' })
            .then(function(r) { return r.json(); })
            .then(function(data) {
                if (data.success) ecnPollStatus();
                else document.getElementById('ecnProgressText').textContent = data.message || 'Failed to start.';
            })
            .catch(function(err) { document.getElementById('ecnProgressText').textContent = 'Error: ' + err.message; });
    });
}

var ecnPollTimer = null;

function ecnPollStatus() {
    if (ecnPollTimer) clearInterval(ecnPollTimer);
    var startTime = Date.now();
    ecnPollTimer = setInterval(function() {
        fetch('/api/ecn-report/status').then(function(r) { return r.json(); }).then(function(data) {
            var elapsed = Math.round((Date.now() - startTime) / 1000);
            if (data.status === 'running') {
                var pct = Math.min(90, elapsed * 3);
                document.getElementById('ecnProgressBar').style.width = pct + '%';
                document.getElementById('ecnProgressText').textContent = 'Running... ' + elapsed + 's elapsed';
            } else if (data.status === 'completed') {
                clearInterval(ecnPollTimer); ecnPollTimer = null;
                document.getElementById('ecnProgressBar').style.width = '100%';
                document.getElementById('ecnProgressText').textContent = 'Complete! Loading data...';
                ecnState.loaded = false; ecnFetchData();
                setTimeout(function() { document.getElementById('ecnProgressWrap').style.display = 'none'; }, 2000);
            } else if (data.status === 'failed') {
                clearInterval(ecnPollTimer); ecnPollTimer = null;
                document.getElementById('ecnProgressBar').style.width = '100%';
                document.getElementById('ecnProgressBar').style.background = '#B8342B';
                document.getElementById('ecnProgressText').textContent = 'Failed: ' + (data.error || 'unknown error');
            }
        }).catch(function() {});
    }, 3000);
}

// ── Download ─────────────────────────────────────────────────────────

function ecnDownloadExcel() { window.location.href = '/api/ecn-report/download'; }

// ── SLA Upload ───────────────────────────────────────────────────────

function ecnToggleSlaUpload() {
    var html = '<div id="ecnSlaUploadModal" style="position:fixed; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,0.4); z-index:2000; display:flex; align-items:center; justify-content:center;">' +
        '<div style="background:#fff; border-radius:8px; padding:24px; max-width:400px; width:90%;">' +
        '<h3 style="margin:0 0 12px;">Upload SLA Template</h3>' +
        '<p style="font-size:13px; color:#6B7280; margin-bottom:12px;">Upload a new ECN Request Classification and Target KPIs Excel file.</p>' +
        '<input type="file" id="ecnSlaFile" accept=".xlsx" style="margin-bottom:12px;">' +
        '<div style="text-align:right;">' +
        '<button class="tool-btn" onclick="ecnCloseSlaUpload()">Cancel</button> ' +
        '<button class="tool-btn" onclick="ecnDoSlaUpload()" style="background:#4a6fa5; color:#fff;">Upload</button>' +
        '</div></div></div>';
    document.body.insertAdjacentHTML('beforeend', html);
}

function ecnCloseSlaUpload() { var m = document.getElementById('ecnSlaUploadModal'); if (m) m.remove(); }

function ecnDoSlaUpload() {
    var fileInput = document.getElementById('ecnSlaFile');
    if (!fileInput.files.length) { appAlert('Select a file first.'); return; }
    var fd = new FormData(); fd.append('file', fileInput.files[0]);
    fetch('/api/ecn-report/upload-sla', { method: 'POST', body: fd })
        .then(function(r) { return r.json(); })
        .then(function(data) { appAlert(data.message); ecnCloseSlaUpload(); })
        .catch(function(err) { appAlert('Upload failed: ' + err.message); });
}

// ── Profile Dropdown ─────────────────────────────────────────────────

function ecnRenderProfileDropdown() {
    var select = document.getElementById('ecnProfileSelect');
    var html = '<option value="">Baseline (Excel Template)</option>';
    for (var i = 0; i < ecnState.profiles.length; i++) {
        var p = ecnState.profiles[i];
        var label = p.name + (p.active ? ' \u2605 ACTIVE' : '');
        html += '<option value="' + p.id + '"' + (ecnState.activeProfile && ecnState.activeProfile.id === p.id ? ' selected' : '') + '>' + label + '</option>';
    }
    select.innerHTML = html;
}

function ecnSelectProfile(profileId) {
    if (!profileId) { ecnState.activeProfile = null; }
    else {
        for (var i = 0; i < ecnState.profiles.length; i++) {
            if (ecnState.profiles[i].id === profileId) { ecnState.activeProfile = ecnState.profiles[i]; break; }
        }
    }
    ecnState.dirtyTargets = {};
    ecnState.currentTargets = ecnMergeTargets(ecnState.baselineTargets, ecnState.activeProfile);
    ecnRefreshUI();
}

function ecnSaveProfileModal() {
    appPrompt('Enter a name for this SLA profile:', '', { placeholder: 'My SLA profile' }).then(function (name) {
        if (!name) return;
        var overrides = {};
        for (var key in ecnState.dirtyTargets) overrides[key] = ecnState.dirtyTargets[key];
        if (ecnState.activeProfile && ecnState.activeProfile.overrides) {
            for (var key in ecnState.activeProfile.overrides) {
                if (!overrides[key]) overrides[key] = ecnState.activeProfile.overrides[key];
            }
        }
        fetch('/api/ecn-report/profiles', { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: name, overrides: overrides })
        }).then(function(r) { return r.json(); }).then(function(data) {
            if (data.success) { ecnState.dirtyTargets = {}; ecnFetchProfiles(); appAlert('Profile "' + name + '" saved.'); }
            else appAlert(data.message || 'Failed to save profile.');
        }).catch(function(err) { appAlert('Error: ' + err.message); });
    });
}

function ecnPromoteProfile() {
    var profileId = document.getElementById('ecnProfileSelect').value;
    if (!profileId) { appAlert('Select a profile to promote.'); return; }
    appConfirm('Promote this profile to the active SLA? All users will see this as the default.', { okText: 'Promote' }).then(function (ok) {
        if (!ok) return;
        fetch('/api/ecn-report/profiles/' + profileId + '/activate', { method: 'PUT' })
            .then(function(r) { return r.json(); }).then(function(data) {
                if (data.success) { ecnFetchProfiles(); ecnState.loaded = false; ecnFetchData(); appAlert('Profile promoted to active SLA.'); }
                else appAlert(data.message || 'Failed to activate.');
            }).catch(function(err) { appAlert('Error: ' + err.message); });
    });
}

// ── Email Modal ──────────────────────────────────────────────────────

function ecnShowEmailModal() {
    fetch('/api/ecn-report/distribution-list').then(function(r) { return r.json(); }).then(function(data) {
        var recipients = (data.recipients || []).map(function(r) { return r.email; });
        var defaultSubject = 'ECN Dashboard \u2014 YTD (as of ' + new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) + ')';
        var html = '<div id="ecnEmailModal" style="position:fixed; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,0.4); z-index:2000; display:flex; align-items:center; justify-content:center;">' +
            '<div style="background:#fff; border-radius:8px; padding:24px; max-width:500px; width:90%;">' +
            '<h3 style="margin:0 0 12px;">Send ECN Dashboard Email</h3>' +
            '<label style="font-size:13px; font-weight:500;">Subject</label>' +
            '<input type="text" id="ecnEmailSubject" value="' + defaultSubject + '" style="width:100%; padding:6px; margin:4px 0 12px; border:1px solid #ccc; border-radius:4px; box-sizing:border-box;">' +
            '<label style="font-size:13px; font-weight:500;">Recipients (one per line)</label>' +
            '<textarea id="ecnEmailRecipients" rows="5" style="width:100%; padding:6px; margin:4px 0 12px; border:1px solid #ccc; border-radius:4px; font-size:13px; box-sizing:border-box;">' + recipients.join('\n') + '</textarea>' +
            '<div style="text-align:right;"><button class="tool-btn" onclick="ecnCloseEmailModal()">Cancel</button> ' +
            '<button class="tool-btn" onclick="ecnSendEmail()" style="background:#4a6fa5; color:#fff;">Send</button></div></div></div>';
        document.body.insertAdjacentHTML('beforeend', html);
    }).catch(function(err) { appAlert('Error loading distribution list: ' + err.message); });
}

function ecnCloseEmailModal() { var m = document.getElementById('ecnEmailModal'); if (m) m.remove(); }

function ecnSendEmail() {
    var subject = document.getElementById('ecnEmailSubject').value;
    var recipients = document.getElementById('ecnEmailRecipients').value.split('\n').map(function(s) { return s.trim(); }).filter(function(s) { return s.length > 0; });
    if (recipients.length === 0) { appAlert('Add at least one recipient.'); return; }
    var stats = ecnComputeStats();
    var htmlContent = ecnBuildEmailHtml(stats);
    fetch('/api/ecn-report/email', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ recipients: recipients, subject: subject, htmlContent: htmlContent })
    }).then(function(r) { return r.json(); }).then(function(data) {
        appAlert(data.message); if (data.success) ecnCloseEmailModal();
    }).catch(function(err) { appAlert('Error: ' + err.message); });
}

function ecnBuildEmailHtml(stats) {
    var date = new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
    var hdr = 'background:#2c3e50; color:#fff; padding:5px 8px; border:1px solid #444; font-size:11px; text-align:center;';
    var hdrL = hdr.replace('center', 'left');
    var td = 'padding:4px 8px; border:1px solid #ccc; font-size:12px; text-align:center;';
    var tdL = td.replace('center', 'left');
    function pctC(v) { return v !== null && v !== undefined ? (v >= 80 ? '#1F8A4C' : '#B8342B') : '#6B7280'; }
    function pctV(v) { return v !== null && v !== undefined ? v + '%' : 'NA'; }

    var html = '<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="color-scheme" content="light dark"></head>';
    html += '<body style="margin:0; padding:0; background:#FAFAF7; font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;">';
    html += '<table width="700" cellpadding="0" cellspacing="0" style="margin:20px auto; background:#fff; border:1px solid #E8E6DF; border-radius:8px;">';

    // Nav header
    html += '<tr><td style="padding:14px 24px; border-bottom:1px solid #E8E6DF;"><span style="color:#6B7280; font-size:12px;">PLM Toolkit / ECN Dashboard</span></td></tr>';

    // Hero
    html += '<tr><td style="padding:20px 24px 10px;">';
    html += '<div style="font-size:11px; text-transform:uppercase; letter-spacing:1px; color:#6B7280;">REPORT \u00b7 ' + date + '</div>';
    html += '<h1 style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:22px; font-weight:bold; margin:6px 0;">' + stats.totalCompleted + ' ECNs Completed YTD</h1>';
    html += '</td></tr>';

    // KPI tiles
    html += '<tr><td style="padding:0 24px 16px;"><table width="100%" cellpadding="0" cellspacing="8"><tr>';
    html += '<td style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; text-align:center; padding:10px;"><div style="font-size:10px; text-transform:uppercase; color:#6B7280;">TOTAL</div><div style="font-size:24px; font-weight:bold;">' + stats.volume.total + '</div></td>';
    html += '<td style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; text-align:center; padding:10px;"><div style="font-size:10px; text-transform:uppercase; color:#6B7280;">STANDARD</div><div style="font-size:24px; font-weight:bold;">' + stats.volume.standard.count + '</div></td>';
    html += '<td style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; text-align:center; padding:10px;"><div style="font-size:10px; text-transform:uppercase; color:#6B7280;">URGENT</div><div style="font-size:24px; font-weight:bold;">' + stats.volume.urgent.count + '</div></td>';
    html += '<td style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; text-align:center; padding:10px;"><div style="font-size:10px; text-transform:uppercase; color:#6B7280;">AVG DAYS</div><div style="font-size:24px; font-weight:bold;">' + stats.ctGeneral.avgDays + '</div></td>';
    var otColor = pctC(stats.ctGeneral.pctOnTarget);
    html += '<td style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; text-align:center; padding:10px;"><div style="font-size:10px; text-transform:uppercase; color:#6B7280;">% ON TARGET</div><div style="font-size:24px; font-weight:bold; color:' + otColor + ';">' + pctV(stats.ctGeneral.pctOnTarget) + '</div></td>';
    html += '</tr></table></td></tr>';

    // === Panel 1: Cycle Time (General ECN) YTD ===
    html += ecnEmailPanel('Cycle Time (General ECN) YTD', stats.ctGeneral);

    // === Panel 2: Cycle Time (PDR ECN) YTD ===
    html += ecnEmailPanel('Cycle Time (PDR ECN) YTD', stats.ctPdr);

    // === Panel 3: Cycle Time (Dedicated Process) YTD ===
    html += ecnEmailPanel('Cycle Time (Dedicated Process ECN) YTD', stats.ctDedicated);

    // === Panel 4: ECN Volume by Change Type ===
    html += '<tr><td style="padding:8px 24px;">';
    html += '<div style="font-size:13px; font-weight:600; color:#333; margin-bottom:6px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;">ECN Volume (Change Type) YTD</div>';
    html += '<table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">';
    html += '<tr><th style="' + hdrL + '">Change Type</th><th style="' + hdr + '">Total</th><th style="' + hdr + '">%</th><th style="' + hdr + '">Avg Days</th></tr>';
    var types = stats.volumeByType;
    for (var i = 0; i < Math.min(types.length, 8); i++) {
        var t = types[i];
        var bg = i % 2 === 0 ? '#fff' : '#FAFAF7';
        html += '<tr style="background:' + bg + ';"><td style="' + tdL + '">' + t.changeType + '</td><td style="' + td + '">' + t.count + '</td><td style="' + td + '">' + t.pct + '%</td><td style="' + td + '">' + t.avgDays + '</td></tr>';
    }
    html += '</table></td></tr>';

    // === Panel 5: Cycle Time by Product Team ===
    html += '<tr><td style="padding:8px 24px;">';
    html += '<div style="font-size:13px; font-weight:600; color:#333; margin-bottom:6px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;">Cycle Time (Product Team) YTD</div>';
    html += '<table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">';
    html += '<tr><th style="' + hdrL + '">Team</th><th style="' + hdr + '">Total</th><th style="' + hdr + '">%</th><th style="' + hdr + '">Avg Days</th><th style="' + hdr + '">% on Target</th></tr>';
    var teams = stats.ctTeam;
    for (var i = 0; i < Math.min(teams.length, 8); i++) {
        var t = teams[i];
        var bg = i % 2 === 0 ? '#fff' : '#FAFAF7';
        html += '<tr style="background:' + bg + ';"><td style="' + tdL + '; font-weight:600;">' + t.team + '</td><td style="' + td + '">' + t.count + '</td><td style="' + td + '">' + t.pct + '%</td><td style="' + td + '">' + t.avgDays + '</td>';
        html += '<td style="' + td + ' font-weight:bold; color:' + pctC(t.pctOnTarget) + ';">' + pctV(t.pctOnTarget) + '</td></tr>';
    }
    html += '</table></td></tr>';

    // === Panel 6: POM (3-month) ===
    if (stats.pom && stats.pom.length > 0) {
        html += '<tr><td style="padding:8px 24px;">';
        html += '<div style="font-size:13px; font-weight:600; color:#333; margin-bottom:2px;">POM ECN KPIs (Monthly)</div>';
        html += '<div style="font-size:10px; color:#6B7280; margin-bottom:6px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;">General ECN type only &middot; 10-day target regardless of priority &middot; over 10 days = overdue</div>';
        html += '<table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">';
        html += '<tr><th style="' + hdrL + '">Value</th>';
        for (var i = 0; i < stats.pom.length; i++) html += '<th style="' + hdr + '">' + stats.pom[i].label + '</th>';
        html += '</tr>';
        // Total row
        html += '<tr style="background:#fff;"><td style="' + tdL + '; font-weight:500;">Total</td>';
        for (var i = 0; i < stats.pom.length; i++) html += '<td style="' + td + ' font-weight:600;">' + stats.pom[i].total + '</td>';
        html += '</tr>';
        // Avg Days row
        html += '<tr style="background:#FAFAF7;"><td style="' + tdL + ';">Avg Days</td>';
        for (var i = 0; i < stats.pom.length; i++) html += '<td style="' + td + '">' + stats.pom[i].avgDays + '</td>';
        html += '</tr>';
        // % on Target row
        html += '<tr style="background:#fff;"><td style="' + tdL + ';">% on Target</td>';
        for (var i = 0; i < stats.pom.length; i++) {
            var p = stats.pom[i].pctOnTarget;
            html += '<td style="' + td + ' font-weight:bold; color:' + pctC(p) + ';">' + pctV(p) + '</td>';
        }
        html += '</tr></table></td></tr>';
    }

    // Footer
    html += '<tr><td style="padding:14px 24px; border-top:1px solid #E8E6DF; text-align:center;">';
    html += '<span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; color:#6B7280;">Generated ' + new Date().toISOString().substring(0, 19) + '</span>';
    html += '</td></tr>';
    html += '<tr><td style="padding:10px 24px 16px; text-align:center;">';
    html += '<div style="font-size:11px; color:#6B7280;">This is an automated notification. Please do not reply to this email.</div>';
    html += '</td></tr>';

    html += '</table></body></html>';
    return html;
}

function ecnEmailPanel(title, ct) {
    var hdr = 'background:#2c3e50; color:#fff; padding:5px 8px; border:1px solid #444; font-size:11px; text-align:center;';
    var hdrL = hdr.replace('center', 'left');
    var td = 'padding:4px 8px; border:1px solid #ccc; font-size:12px; text-align:center;';
    var tdL = td.replace('center', 'left');
    function pctC(v) { return v !== null && v !== undefined ? (v >= 80 ? '#1F8A4C' : '#B8342B') : '#6B7280'; }
    function pctV(v) { return v !== null && v !== undefined ? v + '%' : 'NA'; }

    var html = '<tr><td style="padding:8px 24px;">';
    html += '<div style="font-size:13px; font-weight:600; color:#333; margin-bottom:6px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;">' + title + '</div>';
    html += '<table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">';
    html += '<tr><th style="' + hdrL + '">Value</th><th style="' + hdr + '">Total</th><th style="' + hdr + '">% of Overall</th><th style="' + hdr + '">Avg Days</th><th style="' + hdr + '">% on Target</th></tr>';

    html += '<tr style="background:#fff;"><td style="' + tdL + '">Total</td><td style="' + td + '">' + ct.total + '</td><td style="' + td + '">' + (ct.pctOfOverall || '') + '%</td><td style="' + td + '">' + ct.avgDays + '</td>';
    html += '<td style="' + td + ' font-weight:bold; color:' + pctC(ct.pctOnTarget) + ';">' + pctV(ct.pctOnTarget) + '</td></tr>';

    html += '<tr style="background:#FAFAF7;"><td style="' + tdL + '">Standard</td><td style="' + td + '">' + ct.standard.count + '</td><td style="' + td + '">' + ct.standard.pct + '%</td><td style="' + td + '">' + ct.standard.avgDays + '</td>';
    html += '<td style="' + td + ' font-weight:bold; color:' + pctC(ct.standard.pctOnTarget) + ';">' + pctV(ct.standard.pctOnTarget) + '</td></tr>';

    html += '<tr style="background:#fff;"><td style="' + tdL + '">Urgent</td><td style="' + td + '">' + ct.urgent.count + '</td><td style="' + td + '">' + ct.urgent.pct + '%</td><td style="' + td + '">' + ct.urgent.avgDays + '</td>';
    html += '<td style="' + td + ' font-weight:bold; color:' + pctC(ct.urgent.pctOnTarget) + ';">' + pctV(ct.urgent.pctOnTarget) + '</td></tr>';

    html += '</table></td></tr>';
    return html;
}

// ── Manage Editors ───────────────────────────────────────────────────

function ecnShowEditorsModal() {
    fetch('/api/ecn-report/editors').then(function(r) { return r.json(); }).then(function(data) {
        if (!data.success) { appAlert(data.message); return; }
        var editors = data.editors || [];
        var html = '<div id="ecnEditorsModal" style="position:fixed; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,0.4); z-index:2000; display:flex; align-items:center; justify-content:center;">' +
            '<div style="background:#fff; border-radius:8px; padding:24px; max-width:400px; width:90%;">' +
            '<h3 style="margin:0 0 12px;">Manage ECN Dashboard Editors</h3><div id="ecnEditorsList">';
        for (var i = 0; i < editors.length; i++) {
            html += '<div style="display:flex; align-items:center; padding:4px 0;"><span style="flex:1; font-size:13px;">' + editors[i] + '</span>' +
                '<button class="tool-btn" style="font-size:11px; color:#B8342B;" onclick="ecnRemoveEditor(\'' + editors[i] + '\')">Remove</button></div>';
        }
        html += '</div>' +
            '<div style="margin-top:12px; position:relative;">' +
            '<div style="display:flex; gap:8px;">' +
            '<input type="text" id="ecnNewEditor" placeholder="Type a name or email…" autocomplete="off" ' +
            'oninput="ecnEditorTypeaheadSearch(this.value)" onkeydown="ecnEditorTypeaheadKey(event)" ' +
            'style="flex:1; padding:5px 8px; border:1px solid #ccc; border-radius:4px; font-size:13px;">' +
            '<button class="tool-btn" onclick="ecnAddEditor()">+ Add</button>' +
            '</div>' +
            '<div id="ecnEditorTypeahead" style="display:none; position:absolute; top:32px; left:0; right:64px; background:#fff; border:1px solid #ccc; border-radius:4px; max-height:220px; overflow-y:auto; z-index:10; box-shadow:0 2px 8px rgba(0,0,0,0.1);"></div>' +
            '</div>' +
            '<div style="font-size:11px; color:#6B7280; margin-top:4px;">Suggestions come from the AD group that grants access to PLM Toolkit.</div>' +
            '<div style="text-align:right; margin-top:16px;"><button class="tool-btn" onclick="ecnCloseEditorsModal()">Done</button></div></div></div>';
        document.body.insertAdjacentHTML('beforeend', html);
    }).catch(function(err) { appAlert('Error: ' + err.message); });
}

var _ecnEditorTypeaheadDebounce = null;
var _ecnEditorTypeaheadIdx = -1;

function ecnEditorTypeaheadSearch(query) {
    clearTimeout(_ecnEditorTypeaheadDebounce);
    _ecnEditorTypeaheadDebounce = setTimeout(function() {
        ecnEditorTypeaheadFetch(query.trim());
    }, 200);
}

function ecnEditorTypeaheadFetch(q) {
    var box = document.getElementById('ecnEditorTypeahead');
    if (!box) return;
    fetch('/api/ecn-report/editors/candidates?q=' + encodeURIComponent(q))
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data.success || !data.candidates || data.candidates.length === 0) {
                box.style.display = 'none';
                box.innerHTML = '';
                return;
            }
            var html = '';
            for (var i = 0; i < data.candidates.length; i++) {
                var c = data.candidates[i];
                html += '<div class="ecn-ta-item" data-handle="' + c.handle + '" ' +
                    'onclick="ecnEditorTypeaheadPick(\'' + c.handle.replace(/'/g, "\\'") + '\')" ' +
                    'style="padding:6px 10px; cursor:pointer; border-bottom:1px solid #f0f0f0; font-size:13px;" ' +
                    'onmouseover="this.style.background=\'#f5f7fa\'" onmouseout="this.style.background=\'\'">' +
                    '<div style="font-weight:500; color:#333;">' + (c.displayName || c.username) + '</div>' +
                    '<div style="font-size:11px; color:#6B7280;">' + (c.email || c.username) + '</div>' +
                    '</div>';
            }
            box.innerHTML = html;
            box.style.display = '';
            _ecnEditorTypeaheadIdx = -1;
        })
        .catch(function() { box.style.display = 'none'; });
}

function ecnEditorTypeaheadPick(handle) {
    var input = document.getElementById('ecnNewEditor');
    if (input) input.value = handle;
    var box = document.getElementById('ecnEditorTypeahead');
    if (box) { box.style.display = 'none'; box.innerHTML = ''; }
    ecnAddEditor();
}

function ecnEditorTypeaheadKey(e) {
    var box = document.getElementById('ecnEditorTypeahead');
    if (!box || box.style.display === 'none') {
        if (e.key === 'Enter') { e.preventDefault(); ecnAddEditor(); }
        return;
    }
    var items = box.querySelectorAll('.ecn-ta-item');
    if (e.key === 'ArrowDown') {
        e.preventDefault();
        _ecnEditorTypeaheadIdx = Math.min(_ecnEditorTypeaheadIdx + 1, items.length - 1);
    } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        _ecnEditorTypeaheadIdx = Math.max(_ecnEditorTypeaheadIdx - 1, 0);
    } else if (e.key === 'Enter') {
        e.preventDefault();
        if (_ecnEditorTypeaheadIdx >= 0 && _ecnEditorTypeaheadIdx < items.length) {
            ecnEditorTypeaheadPick(items[_ecnEditorTypeaheadIdx].getAttribute('data-handle'));
        } else {
            ecnAddEditor();
        }
        return;
    } else if (e.key === 'Escape') {
        box.style.display = 'none';
        return;
    }
    for (var i = 0; i < items.length; i++) {
        items[i].style.background = (i === _ecnEditorTypeaheadIdx) ? '#e8f0fe' : '';
    }
}

function ecnCloseEditorsModal() { var m = document.getElementById('ecnEditorsModal'); if (m) m.remove(); }

function ecnRemoveEditor(username) {
    ecnUpdateEditorsList(function(editors) { return editors.filter(function(e) { return e !== username; }); });
}

function ecnAddEditor() {
    var input = document.getElementById('ecnNewEditor');
    var username = input.value.trim(); if (!username) return;
    ecnUpdateEditorsList(function(editors) { if (editors.indexOf(username) < 0) editors.push(username); return editors; });
    input.value = '';
}

function ecnUpdateEditorsList(transform) {
    fetch('/api/ecn-report/editors').then(function(r) { return r.json(); }).then(function(data) {
        var editors = transform(data.editors || []);
        return fetch('/api/ecn-report/editors', { method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ editors: editors }) });
    }).then(function(r) { return r.json(); }).then(function(data) {
        if (data.success) { ecnCloseEditorsModal(); ecnShowEditorsModal(); }
        else appAlert(data.message || 'Failed to update editors.');
    }).catch(function(err) { appAlert('Error: ' + err.message); });
}
