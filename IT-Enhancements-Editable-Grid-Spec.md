# IT Enhancements — Editable Grid: Implementation Spec

**Goal:** Extend the IT Enhancements tab from a single editable column (`Target UAT`) to a
spreadsheet-style editable grid covering the IT-owned fields developers actually maintain.

**Visual + interaction source of truth:** the approved prototype in this folder —
`IT Enhancements Editable Grid.html` (+ `grid.jsx`, `data.js`, `tokens.css`). Match its
look and behavior; it is built on the app's existing design tokens.

> The prototype is React for fast iteration. **Production stays vanilla JS** — port the
> behavior into the existing `it-enhancements.js`, do not introduce React.

---

## 1. Files in play (real repo)

| File | Role | Change |
|---|---|---|
| `src/main/resources/static/it-enhancements.js` | Grid render + inline edit + dirty/save | **Major** — generalize from 1 editable col to N |
| `src/main/resources/static/index.html` (`#panelItEnhancements`) | Panel markup, toolbar, bands, pills | **Minor** — copy intro, density toggle, legend |
| `src/main/java/.../controller/ItEnhancementsController.java` | `/data`, `/save-cell`, `/save-batch` | **Small** — mirror the cell allowlist before forwarding |
| `src/main/java/.../service/AgileWriteBackClient.java` | `updateChangeCellAsUser(...)` | Likely **none** (already forwards `cellName`/`value`) |
| `plm-agile-service` → `PerUserChangeUpdateController` | Transient-session write to Agile | **Major** — extend allowlist + handle non-date cell types |
| `src/main/java/.../service/ItEnhancementsService.java` (`Row`) | Read model | None for read; confirm cell names below |

---

## 2. Editable field set (approved scope)

Editable by the developer. Everything else stays **read-only and visually muted**.

| Column (`Row` field) | UI editor | Agile cell name (⚠ VERIFY) | Notes |
|---|---|---|---|
| `itOwner` | dropdown (list) | `Page Three.IT Owner` | single-select listentry; write loginId, display name |
| `hours` | number input | `Page Three.Estimated Hours` | `page_three.TEXT37` today — confirm it's the hours cell |
| `project` | text + suggestions | `Page Three.Project` | `agile_flex 251747921` — **multi-value** |
| `itActions` | text + suggestions | `Page Three.IT Actions Taken` | `agile_flex 251748003` — **multi-value** |
| `targetUAT` | date | `Page Three.Target UAT Date` | `page_three.DATE36` — already implemented |
| `targetGoLive` | date | `Page Three.Target Go-Live Date` | `page_three.DATE37` |
| `itLog` | long-text popover | `Page Three.IT Log` | `agile_flex_clob 2000025513` |

**Read-only:** `ecnNumber`, `priority`, `status` (IT Status), `workflowStatus`, `requestor`,
`category`, `problemStatement`, `proposal`, `reworkReason`, `submitDate`, `releaseDate`.

> ⚠ **The cell names above are plausible guesses except `Target UAT Date`.** Confirm every
> one against `~/Downloads/IT_Enhancements_Field_Mapping.md` and/or the Agile attribute
> metadata **before** wiring writes. A wrong cell name is a silent no-op or a write to the
> wrong attribute.

---

## 3. Column model (replace the flat `COLUMNS` in `it-enhancements.js`)

Add per-column type + editability + cell name. Mirror `data.js` → `COLUMNS` in the prototype:

```js
// { key, label, w, type, edit, cell }
// type: 'ecnLink'|'priority'|'status'|'text'|'person'|'wrap'
//       |'number'|'date'|'select'|'longtext'
// edit: true => developer-editable
// cell: Agile cell name sent on save (editable cols only)
```

`renderCell()` switches on `type`. Editable cells get a hover affordance and, when active,
a fill handle. The current `dateEdit`/`ite-edit-uat` special-case is replaced by the generic
`edit` flag.

---

## 4. Interaction model (spreadsheet)

Replicate from the prototype (`grid.jsx`):

- **Select:** single click → active cell with a 2px accent ring. Track `active = {ecn, colKey}`
  (or `{ri, ci}` over the filtered list).
- **Edit start:** `Enter`, double-click, or typing a printable char (text/number cols seed
  with that char). `date`/`person` open their native control; `longtext` opens a popover.
- **Commit:** `Enter` commits + moves **down** one row (same column); `Tab` commits + moves to
  the **next editable cell** (wraps to next row); blur commits. `Esc` cancels.
- **Navigate (not editing):** arrow keys move the active cell; `Tab`/`Shift+Tab` jump between
  editable cells only.
- **Delete:** `Backspace`/`Delete` on an editable non-longtext cell clears it.
- **Fill-down (the key power feature):**
  - drag the bottom-right **fill handle** down the column, **or**
  - `Shift+click` / `Shift+↓` to select a vertical range, then `⌘/Ctrl+D`.
  - Fills the anchor cell's effective value into the target rows as pending edits.
- Manual scroll-into-view nudge for the active cell (do **not** use `scrollIntoView`).

---

## 5. Dirty / save state machine

Generalize the current `STATE.dirty[ecn]['Page Three.Target UAT Date']` to any cell.

Per edited cell, status ∈ `pending | saving | saved | error`:

- **pending** — edited, not saved → amber tint + amber corner dot; counts toward
  `Save all changes (N)`.
- **saving** — in-flight after Save → blue pulsing dot.
- **saved** — write succeeded → green dot/tint, then fade and clear after ~2s; patch the
  committed row value locally (generalize the existing
  `if (res.cellName === 'Page Three.Target UAT Date') row.targetUAT = ...` to a
  `cellName → field` reverse map so any saved cell updates its row).
- **error** — validation failure or Agile rejection → red dot/tint + tooltip with the reason;
  excluded from the batch; stays dirty for the user to fix.

**Save flow:** unchanged batching — gather `pending` cells → `POST /save-batch` with
`{edits:[{ecn, cellName, value}]}` → apply per-row `results`. Keep the existing
`needsAgileSignin` modal + retry path; it already works for arbitrary cells.

**Legend** (footer): the four state dots + a keyboard cheatsheet — copy from the prototype.

---

## 6. Validation (light, recommended)

Block obviously-bad writes client-side before they hit Agile:

- `hours`: empty or non-negative number.
- `targetGoLive`: not earlier than `targetUAT` on the same row.

Invalid cells go straight to `error` status (not `pending`) so they're excluded from Save and
flagged red. Keep it minimal; the server remains the source of truth.

---

## 7. Backend changes

### 7a. `ItEnhancementsController` (toolkit)
`/save-cell` and `/save-batch` already forward `cellName` + `value` to
`agileWriteBackClient.updateChangeCellAsUser(...)`. Add a **server-side allowlist mirror**
here as defense-in-depth: reject any `cellName` not in the approved set with a per-row
`{ok:false, error:"cell not editable"}` rather than forwarding it.

```java
private static final Set<String> EDITABLE_CELLS = Set.of(
    "Page Three.Target UAT Date",
    "Page Three.Target Go-Live Date",
    "Page Three.Estimated Hours",
    "Page Three.IT Owner",
    "Page Three.Project",
    "Page Three.IT Actions Taken",
    "Page Three.IT Log"
); // names MUST match §2 after verification
```

### 7b. `PerUserChangeUpdateController` (plm-agile-service) — the real gate
- **Extend the allowlist** (currently just `Page Three.Target UAT Date`) to the §2 set.
- **Handle non-date cell types** — the current path assumes an ISO date string. Add handling for:
  - **list / single-select** (`IT Owner`): resolve the value to a listentry / set the cell's
    selection via the SDK, not a raw string.
  - **multi-list** (`Project`, `IT Actions Taken`): parse multiple values and set the
    multi-list selection.
  - **number** (`Estimated Hours`): set as text/number per the attribute's data type.
  - **clob** (`IT Log`): set the long-text value.
- Keep the transient-session-under-the-user's-identity model and the
  `finally { close session }`. **Never log the password or the clob contents.**
- Echo `{oldValue, newValue}` per cell so the UI can confirm normalization (esp. dates and
  list display values).

---

## 8. Acceptance criteria

1. All seven §2 columns are editable inline; all other columns are read-only and muted.
2. Click→type→`Tab`/`Enter` flows across editable cells; arrows navigate; `Esc` cancels.
3. Fill handle drag **and** `⌘/Ctrl+D` fill a value down a selected range.
4. Edited cells show pending→saving→saved/error with tint + corner dot; `Save all changes (N)`
   reflects the pending count.
5. `Save all` batches to `/save-batch`; successes patch the local row and clear; the Agile
   **History tab attributes each edit to the signed-in user, not Administrator**.
6. List/multi-list/number/clob writes land correctly in Agile (verify in Web Client History).
7. `needsAgileSignin` modal + retry still works for the new cells.
8. Styling uses `tokens.css` variables — no new hard-coded colors.

---

## 9. Gotchas

- **Verify cell names first** (§2). This is the most likely source of silent failures.
- **Multi-value fields** (`Project`, `IT Actions Taken`) are not plain strings — the read joins
  them with `, `; the write must set a multi-list selection, not a comma string.
- **`IT Owner` is a person/list** — store/display name vs. write loginId may differ; confirm
  what the cell expects.
- **Snapshot cache:** `/data` serves the hourly JSON snapshot. After a save, patch the row
  locally (already done for UAT) so the grid reflects the edit without waiting for the next
  rebuild or a `Refresh now`.
- **Keep batch save** — do not auto-save per cell; the user asked to review before writing.
- **Don't use `scrollIntoView`** — nudge `scrollTop`/`scrollLeft` manually (see prototype).
