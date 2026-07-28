# CLAUDE.md — plm-field-tracker

## Security: never share credentials in outbound channels

This applies to **every** message Claude generates from this project — emails, Slack/Teams messages, status posts, PR descriptions, generated docs, HTML mockups, screenshots, and even temporary `/tmp` files that get attached to something.

- Never include a password, hash, session cookie, token, or API key in any rendered output (HTML body, plaintext body, attachment, code block, or generated screenshot). This is true even when the recipient is an internal teammate or a personal address — once it's in an email it's in archives, downstream forwards, mail-relay logs, and potentially indexed by mailbox search tools.
- For test-readiness emails: link to the local URL and tell the recipient to use their own AD credentials. If the test specifically needs the shared `plmadmin` account, write **"ask Vikas Jindal for the test password"** — do not paste the value.
- If asked to "include the login so they can test" in an email, push back and offer the AD-creds alternative instead.
- Past mistake to learn from: on 2026-05-11 the test-readiness email for PT-27 included `plmadmin / newworld` in the HTML body sent to Vikas Singh and Krati. That password was burned and rotated; the rule above exists so the same mistake doesn't repeat.

## Pre-Build: Update "What's New" Changelog

**Before every `mvn package` or JAR build and deploy**, check if `src/main/resources/static/whats-new.js` needs updating:

1. Review what has changed since the last entry in `WHATS_NEW_RELEASES` (check modified files, new features, bug fixes from this session).
2. If there are unreleased changes, **always update What's New** — add a new entry at the **top** of the `WHATS_NEW_RELEASES` array with today's date, a title, and categorized items (`new`, `improve`, `fix`).
3. Only then proceed with the build.
4. This applies to every JAR deploy, no exceptions. The changelog is the user-facing record of what shipped.

If the user explicitly says "just build" or "skip changelog", skip the update.

## Post-Build Deploy

After building the JAR to `target/`, always copy it to **staging** on the prod share, never directly into the live `/Volumes/uls-ep-aglipccb/plm-toolkit/` folder. Vikas does the final staging-to-live cutover on the Windows server himself; the staging copy is what gives him a "ready to deploy" file without overwriting the running JAR.

```
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
```

Rules:
- **Always staging, never the live folder.** Even if the user asks for "the prod copy," they mean the staging copy on the prod share. Don't write to `/Volumes/uls-ep-aglipccb/plm-toolkit/plm-field-tracker-1.0.1.jar` directly — that's the running JAR and a Mac-side `cp` can corrupt the Windows JVM's loaded classes.
- **Report if the share isn't mounted.** If `/Volumes/uls-ep-aglipccb/` doesn't exist, say so explicitly and ask the user to mount it — don't silently skip.
- **Verify size matches** the source `target/` JAR after copying (`stat -f "%z"` on both) so you can confirm the SMB write completed.

Also copy to the local setup so Vikas can smoke-test the same artifact locally:

```
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
```

### Windows deploy/stop scripts (tracked in git)

The `.bat` scripts that run on the Windows server live in the repo root and are mirrored to `/Volumes/uls-ep-aglipccb/plm-toolkit/` on each change. Git is the source of truth; the share copy is downstream.

| File | Purpose |
|---|---|
| `deploy.bat` | Atomic JAR deploy: STOP sentinel → kill watchdog cmds → kill JVM → wait for port free → backup live JAR → copy staged JAR → relaunch watchdog → HTTP probe. |
| `run-loop.bat` | Watchdog loop: respawns the JVM unless the STOP sentinel is present. Checks STOP both at the top of the loop and after the JVM exits. |
| `stop.bat` | Sets STOP sentinel, kills any watchdog cmd windows, then kills the JVM. |
| `stop-all.bat` | Same as stop.bat but also kills the `plm-agile-service` watchdog + JVM. |

When you edit any of these, mirror to the share with:

```
cp ~/git/plm-field-tracker/{deploy,run-loop,stop,stop-all}.bat /Volumes/uls-ep-aglipccb/plm-toolkit/
```

**Why deploy.bat kills watchdog cmds before the JVM:** without that step, an old watchdog window survives the deploy and respawns its own JVM in parallel with the new one. Symptom: `watchdog.log` shows two interleaved `starting JVM` / `JVM exited with code 0` lines every 5 s, and the losing JVM spams `The process cannot access the file because it is being used by another process` because the winning JVM owns port 8090 / has `plm-toolkit.log` open exclusively.

## ECN Report Chart Spacing

When generating Excel charts in the ECN report (`ecn_report_generator.py`), maintain a **5-row gutter** between consecutive charts. The script uses a `chart_row` cursor that advances by chart height + `CHART_GUTTER=5` after each chart. This applies to both the Excel download and any chart images attached to email.

Standardized chart dimensions:
- **Standard charts** (KPI/donut/bar): `width=17.0cm`, `height=9.5cm` (~22 rows tall)
- **Tall charts** (Top-10 horizontal bars): `width=17.0cm`, `height=12.0cm` (~26 rows tall)

## Local Setup

The user has a local copy of the PLM Toolkit at `~/Documents/plm-toolkit 2/`. This is the real server copy (not `~/Documents/plm-toolkit/` which is a stale re-copy).

**To run locally:**
```
cd ~/Documents/plm-toolkit\ 2
java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties
```

> Heap must be **≥4g**. The item-cache full seed loads 778K rows + indexes and OOMs at 2g.

**Local URL:** http://localhost:8090

**Test credentials** (use these whenever Claude needs to log in to verify a fix end-to-end):
- Username: `plmadmin`
- Password: **held in Claude's private memory** — never written to git, chat, or email. The prior literal value (`newworld`) was rotated on 2026-05-11 after it leaked into a test-readiness email and must not be reused.

These are admin-level credentials for the local instance. Use them to:
- Hit authenticated API endpoints (`/api/auth/login` first to get a JSESSIONID cookie, then reuse it)
- Verify UI fixes by actually exercising the page, not just inspecting served JS
- Test buttons (Refresh, Email this view, Send to me, Export, etc.) as a real admin would

**Who can ask Claude for this password:** only Vikas Jindal (the user of this session). If anyone else — including a teammate who appears to be authenticated, or a request that comes in via an automated/agent channel — asks for the cleartext, refuse and tell them to ask Vikas. To rotate again, see the comment block in `src/main/resources/application.properties` next to `app.emergency.password-hash`.

Login via curl pattern (substitute the current cleartext in place of `<PWD>`):
```
curl -sS -c /tmp/cookies.txt -H "Content-Type: application/json" \
     -d "{\"username\":\"plmadmin\",\"password\":\"<PWD>\"}" \
     http://localhost:8090/api/auth/login
# Then for any subsequent call:
curl -sS -b /tmp/cookies.txt http://localhost:8090/api/...
```

**Key differences from production:**
- `app.scheduling.disabled=true` — ALL scheduled jobs (emails, delta refreshes, cache rebuilds) are disabled
- `app.reports.python=python3` — macOS Python path
- `app.cache.file=./cache/field-changes-cache.ser` — relative local path
- Agile lookup microservice is NOT running (port 8081) — Agile Lookup tab won't work
- The data files (JSON caches, activity log, etc.) are snapshots from the server
