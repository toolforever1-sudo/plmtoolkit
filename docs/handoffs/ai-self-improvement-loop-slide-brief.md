# Slide brief — AI Self-Improvement Loop

**Hand-off:** to a design AI (or a human designer) for production of one slide.
**Audience:** SanDisk PLM leadership / cross-functional review.
**Time on slide:** ~60–90 seconds of talk track. Single screen, no animation required.

---

## The big idea (one line)

> **Humans set the goal. The AI agent rewrites itself until the goal is met — and reports back honestly when it isn't.**

The slide shows a closed feedback loop where a single human "expectation" becomes a self-driven cycle of code change → deploy → retest → verdict, repeating until the human is satisfied.

---

## What's on the slide

### Headline (top)
**The Self-Improvement Loop**
*Sub-headline:* From "this answer is wrong" to "fix shipped and retested" — without a developer in the middle.

### The loop diagram (centerpiece)

A clockwise circular flow with **5 nodes**. Use the SanDisk palette below — single accent color (`#4a6fa5`, primary blue) for the arrows; nodes alternate between white card and `#FAFAF7` tile fills.

| # | Node label | One-line under the label |
|---|---|---|
| 1 | **Goal** | User grades an AI answer C / D / F and types what they expected |
| 2 | **Detect** | A poller scans the bug queue every few minutes and picks up the graded item |
| 3 | **Fix** | Agent edits the code, builds the artifact, schedules a maintenance window, deploys |
| 4 | **Test** | Agent re-asks the original question and compares the new answer to the expectation |
| 5 | **Report** | Agent emails a *before vs after* with an honest verdict — "matches" / "still off" / "no change" |

A thin dashed return-arrow from **Report → Goal** with the label *"if not met, loop runs again"* — this is the self-improvement part.

### Side panel (right of the loop — narrow column)

A small **Guardrails** box (use the success/warning callout style from the email design guide):

- **Quality gate at the goal stage:** an AI validator rejects gibberish or empty expectations *before* the bug is filed, so the loop never wastes a cycle on noise.
- **Ambiguous goal? Don't guess — ask.** If the user's expectation can be read two ways, the agent emails the human instead of attempting a fix.
- **Don't-touch list.** Auth, permissions, LDAP, DB schemas, scheduled jobs, and Python report scripts always escalate to human review — even with a clear goal.

### Bottom strip — one concrete example

Use a small two-column "before / after" rendering with the actual story:

> **Goal received:** *"how many SKUs were created last week? — expected ~count, got 0 because the filter was on the wrong column"*
> **What the agent did:** Located the SQL filter, switched `NEW_PART_CLASS` to the SKU-detection column, rebuilt, redeployed with a 2-minute maintenance window, re-asked the question.
> **Verdict emailed back:** *Before:* "0 items match." → *After:* "42 SKUs created since 2026-05-05." **Goal met.**

(If this exact example hasn't run end-to-end yet, swap in "Illustrative" as a subtle caption on the box.)

---

## Visual direction

Follow the SanDisk email design guide so the slide reads as a sibling of the automated emails the loop produces.

### Palette (use sparingly — this is a calm, executive slide, not a colorful infographic)

| Role | Hex |
|---|---|
| Primary accent (arrows, "goal met" tick) | `#4a6fa5` |
| Header / dark surface | `#2c3e50` |
| Page background | `#FAFAF7` |
| Card / node fill | `#ffffff` |
| Border / hairline | `#E8E6DF` |
| Ink (primary text) | `#0F1720` |
| Muted text (sub-headlines, captions) | `#6B7280` |
| Success ("goal met") | `#1F8A4C` |
| Warning ("ambiguous → human") | `#C7801B` |
| Error ("don't-touch — escalate") | `#B8342B` |

### Typography

- **Slide titles / node labels:** `IBM Plex Serif` bold, sized down from the email guide (titles ~28pt on a slide, node labels ~14pt).
- **Body / captions:** `IBM Plex Sans` regular.
- **Verdict snippets / time stamps:** `IBM Plex Mono` 11pt.

### Layout

- 16:9 slide. Keep generous whitespace on the outer 8% margin.
- Loop diagram occupies the **left ~65%**; guardrails panel the **right ~30%**, separated by a thin `#E8E6DF` rule.
- The bottom "example" strip is a **full-width band** with a 1px top border in `#E8E6DF`.

### What NOT to do

- No emojis, no chat-bubble icons, no robot icons. This is a workflow diagram, not a personality piece.
- No drop shadows. Keep nodes flat with a 1px border.
- No more than one accent color on the arrows. The variety in the *nodes* comes from labels, not colors.
- No reference to "WDC" or "Western Digital" — SanDisk only.

---

## Talk track (for the presenter, not on the slide)

> "Today, when our PLM AI gives a bad answer, the cost of fixing it is a developer ticket, a code review, and a deploy. With this loop, the user's correction *is* the work item. The agent does the diff, the build, the maintenance window, the retest, and the honest report-back. Humans stay in the loop where it matters — setting the goal, and deciding whether the answer is good enough — and out of the loop where the agent can carry the rope itself."

End-of-slide line: **"The user's expectation is the spec."**

---

## File location

Save the rendered slide as `docs/slides/ai-self-improvement-loop.png` (or .pdf) when produced. This brief lives at `docs/handoffs/ai-self-improvement-loop-slide-brief.md`.
