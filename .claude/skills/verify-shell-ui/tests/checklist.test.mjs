// Structural test for verify-shell-ui/SKILL.md: the shared checklist must cover
// every shared surface (+ the 401 bridge proof), both platform lanes must be
// present, and the copy-string mutation run's contract must be encoded. Text-only assertions
// on SKILL.md itself — no simulator/emulator required (see skill-doctor's
// checklist item 1).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const md = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'SKILL.md'), 'utf8');

test('shared checklist covers every shared surface', () => {
  for (const s of ['PAIR', 'ENROLL', 'HOME', 'Manual code entry']) assert.match(md, new RegExp(s));
  assert.match(md, /401/); // the bogus-code bridge proof
});

test('has both platform lanes and a mutation gate', () => {
  assert.match(md, /iOS:/);
  assert.match(md, /Android:/);
  assert.match(md, /Mutation gate/i);
});

test('honestly flags which checklist items need driving vs are screenshot-only', () => {
  // The plan assumed idb driving works; on this machine idb-companion is broken,
  // so the skill must not silently claim full driven coverage. Every item other
  // than PAIR needs interactive input (type/tap) to reach.
  assert.match(md, /requires driving \(idb\)/i);
  assert.match(md, /screenshot/i);
  assert.match(md, /idb/); // idb-optional framing must be present at all
});

test('points at the real primitives script and parity contract', () => {
  // auto-login tracks both at daemon/ (the pack copy said packages/custody-daemon/, MVP's old layout).
  assert.match(md, /`daemon\/scripts\/verify-shell-ui\.sh/);
  assert.match(md, /`daemon\/shells\/PARITY\.md`/);
  assert.doesNotMatch(md, /packages\/custody-daemon/);
});

test('routing note distinguishes this skill from run and from the repo\'s own checks', () => {
  assert.match(md, /distinct from `run`/);
  assert.match(md, /the repo's own checks/);
  assert.doesNotMatch(md, /`verify`|preflight-gate|mutation-gate/, 'no skill that does not exist in this repo');
});
