---
name: verify-shell-ui
description: Screenshot-first build-run-see loop for the custody iOS-simulator / Android-emulator shells that grades every reachable screen against shells/PARITY.md's shared + per-platform parity checklist, drives deeper (bogus-code to 401, ENROLL, HOME) via idb when available, and proves the loop isn't vacuous with a copy-string mutation gate. Use when a custody shell's UI changed and nobody has seen it on a simulator or emulator, when a shell screen may have drifted from PARITY.md, or when a pairing, enroll or home screen is reported wrong. NOT for launching an arbitrary app (run) or the repo's own checks before a push.
---

# verify-shell-ui

Build→run→see loop for the custody shells. The primitives live in
`daemon/scripts/verify-shell-ui.sh <ios|android>`; this skill is the
judgement layer: it runs the primitives, reads the screenshots, and grades them
against `daemon/shells/PARITY.md`. Never a merge gate.

## Reality check (read this before trusting a "full" run)

The plan behind this skill assumed `idb` for interactive driving (tap/type). On
this machine `idb-companion` is broken (the `facebook/fb` Homebrew formula ships
no binary), so **the loop is screenshot-first today**:

- `xcrun simctl` reliably builds, boots a simulator, installs, launches, and
  screenshots every screen reachable **without input** — proven: the PAIR screen
  renders with the correct PARITY.md copy on an iPhone 17 Pro simulator.
- Interactive driving (typing a pairing code, tapping Pair, filling ENROLL) is
  gated behind `idb` and is **optional** — `verify-shell-ui.sh ios` detects
  whether `idb` is on PATH and only attempts it then; otherwise it prints a note
  and stops after the screenshot.
- Do not report ENROLL/HOME/bogus-code-401 as "verified" from this loop alone
  until `idb` (or an equivalent driver) is actually available and used.

## Preflight

Run `cd daemon && make doctor`. Expect the iOS simulator lane's
`xcodegen` and simulator-runtime checks to be ✓; `idb` is currently ✗ on this
machine and that is fine — it only disables the driving lane below, not the
screenshot lane. (Android: SDK/NDK per `BUILD.md`.)

## Run

`daemon/scripts/verify-shell-ui.sh ios --evidence <dir>` (or `android`).
Then Read the numbered PNGs and grade them against the checklist below.

## Shared checklist (BOTH platforms — from PARITY.md shared surfaces)

1. **PAIR** renders (title "Pair this device", "Pairing code" field, "Pair"
   button) — **verifiable today via screenshot** (`01-launch.png`).
2. **Bogus pairing code** surfaces the hub's 401 error string ("Pairing failed:
   …") cleanly, proving the gomobile bridge + real HTTPS (matches MVP #2922's
   Android proof, from before the daemon moved to this repo) — **requires driving (idb) — currently a follow-up.**
3. **ENROLL** persists a login (title "Add a login"; save a test site) —
   **requires driving (idb) — currently a follow-up.**
4. **HOME** lists the enrolled site ("Stored logins (1)") — **requires driving
   (idb) — currently a follow-up** (reachable only after ENROLL).
5. **Manual code entry** accepts a typed code — **requires driving (idb) —
   currently a follow-up.**

## Per-platform checklist (divergent — graded against PARITY.md, not the other shell)

- **iOS:** the Shortcuts-setup wizard renders its guided steps; a webhook test
  round-trip surfaces a success/health state. (Present once the SMS lane ships.)
- **Android:** flipping the "Fill in codes from a text" Settings toggle raises the
  **`RECEIVE_SMS` runtime permission prompt** once, and denying it leaves the toggle
  off. There is no per-message consent sheet — the User Consent API was reverted
  (MVP #3095). For the email lane, "Link a mailbox" raises **no OS prompt at all**; the
  IMAP app-password is the grant.

## Evidence output

A pass/fail table (checklist item → PASS/FAIL/SKIPPED(no idb) → screenshot
filename) plus the PNG dir. Report failures with the offending screenshot, and
mark driving-gated items SKIPPED rather than PASS when `idb` was unavailable.

## Mutation gate (proves the loop is non-vacuous)

Screenshot-only today, scoped to the one item that doesn't need driving — PAIR:

1. Change the PAIR button copy in `daemon/shells/ios/Sources/PairingView.swift`
   (`PrimaryButton("Pair", …)` → `PrimaryButton("PairX", …)`).
2. Re-run `verify-shell-ui.sh ios`; the new `01-launch.png` MUST show "PairX",
   i.e. grading the screenshot against the checklist MUST flag the mismatch.
3. `git checkout` the file and re-run; confirm the screenshot is back to "Pair"
   (clean/green).

Once `idb` is available, extend the gate to a driven item (e.g. mutate the 401
error prefix and confirm a driven bogus-code run flags it). See
`tests/checklist.test.mjs` for the encoded contract.

## Checklist dispositions

- Unit test: `node --test .claude/skills/verify-shell-ui/tests/checklist.test.mjs`, 5/5 (the checklist item set is complete, and the copy-string mutation run's contract is encoded: a mutation run local to the skill -- one copy string changed must turn the grade red, and the reverted file must turn it green again).
- Integration test: `verify-shell-ui.sh ios` against a built app on a booted simulator (needs a Mac + toolchain per `make doctor`); today this exercises the screenshot lane only.
- E2E smoke: the first iOS shared-surface port run (Tasks 5–6), screenshot-graded end-to-end; full driven coverage (item 2–5 above) follows once `idb` is fixed or swapped for another driver.
- Routing: this is the UI-screenshot verification loop for the custody shells; distinct from `run` (generic launch-and-screenshot the app) and from the repo's own checks (the adapter's `tests` command, and the repo's pre-push hook where it has one; here `make -C daemon test` and `.github/workflows/ci.yml`).
- Build recipe: [references/gomobile-build.md](references/gomobile-build.md) -- the custody daemon's Android bind and APK build, the recipe `make -C daemon android` runs.

## Self-improvement

**Purpose (what a fold may never change).** A screenshot-first build-run-see
loop that grades the custody shells' reachable screens against
`daemon/shells/PARITY.md`. A change that would alter that sentence is a new
skill or a merge through a skill review, never a fold.

**This is a one-shot skill:** folds come only from the maintenance triggers
below, never as a self-edit at the end of an invocation.

**What counts as a measured gap worth folding:**
- a trigger it missed: a shell UI change nobody graded, found in a transcript;
- a step weaker agents skipped or misread, such as a driving-gated item
  reported as PASS without `idb`;
- a claim that went stale: a path, a copy string, a command (the pack copy
  pointed at MVP's old daemon path after the daemon moved here);
- a failure the skill did not prevent: a shipped screen that drifted from
  PARITY.md;
- a correction from the user: a cue that failed. Name the skill that should
  have fired (a recall gap), the skill that is missing (a coverage gap), or the
  rule that was wrong (a content gap), and file or fold it in the same turn.

**How a fold is written.** One numbered entry, under about five lines, IN the
section the rule belongs to, naming the measurement that caused it and the rule
it changed. Past about twenty entries, the dated log moves to
`references/folds.md`, never SKILL.md.

**Maintenance triggers:** a skill review over this repo's `.claude/skills`
(content and coverage, and use from transcripts); `tests/checklist.test.mjs`
on every change to this file; and PARITY.md changing.

**Done line:** `fold <n> <date>: <measurement> -> <rule changed>; purpose unchanged`.
