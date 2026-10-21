# Saci findings from the operator plan

## 1. Parent units re-present completed procedure invocations without their evidence

- **Status:** Fixed in current Saci source by `58745b891fa40ccb9d1e5c4fd8f13f061cd30063`
  ("Give invoking units their procedure results and evidence (#78)", October 21, 2026).
  Verified against local revision `c76967b72707da177926411269b4320a4adb0aa4`: procedure summaries now
  include assessment evidence IDs, and the parent prompt explicitly identifies completed procedures and
  prohibits searching for or rerunning their documents. Do not file this historical finding as a new open bug.
  This source verification does not establish the revision embedded in an arbitrary worker image.
- **Severity:** Medium; adds redundant work and consumes the run deadline.
- **Run:** `run-a0835c6acc6b4db38018813c6e3fcae1`, parent `S.3` and child
  `S.3>common/wait-for-deletion.md#0`.
- **Reproduction:** run a parent step whose action is `Follow [common/wait-for-deletion.md](common/wait-for-deletion.md)`
  and whose criterion is that the helper was defined. The procedure defines the helper and passes before the parent.
- **Observed:** the parent searched the worker filesystem repeatedly for `wait-for-deletion.md`, even though the
  procedure had already executed. Evidence `ev-15` defines the helper and `ev-16` confirms it; the parent subsequently
  performs filesystem searches in `ev-17` through `ev-19`. Its four model requests took 95,503 ms. The parent eventually
  passed; this is not a claim that every procedure executes twice.
- **Expected:** the parent receives an explicit completed-invocation context and the child evidence needed to
  assess its own criteria, without treating the original `Follow` as a fresh instruction to find or rerun the file.
- **Historical source verification** (Midnight Saci revision `efad342`):
  - `crates/saci-core/src/bundle.rs:229`: procedure units are expanded before the parent, which keeps the authored action.
  - `crates/saci-worker/src/sequence.rs:190`: earlier results retain key/title/outcome/explanation, not child command
    evidence, evidence IDs or an explicit completed-procedure marker.
  - `crates/saci-worker/src/agent/prompt.rs:78`: the raw `Follow` action is rendered again. The instruction to reuse
    already-run commands at line 130 covers the current unit's `run` blocks, not expanded procedures.
  - `crates/saci-worker/src/agent/mod.rs:284`: each unit starts a fresh conversation.
  - `crates/saci-worker/src/agent/tools.rs:167`: child evidence IDs are valid in the shared recorder, but are not
    reliably exposed to the parent prompt.
- **Suggested fix:** retain procedure invocation/completion metadata and pass the associated evidence IDs and
  observations to the parent; explicitly distinguish completed `Follow` invocations from remaining actions.

## 2. Shared-shell state disappears after a command timeout

- **Status:** Reproduced in consecutive OpenShift operator-plan executions; source cause unconfirmed.
- **Severity:** High; breaks dependent assertions and can compromise cleanup verification.
- **Run:** `run-1e9a305275734b4bbf26f7fe98f80439`.
- **Trigger:** Test 7.4's Barn readiness command (`ev-92`) times out after 122007 ms.
  The underlying Barn readiness failure is recorded separately in `wanaku-bugs.md`.
- **Observed:** Subsequent test 8.1 (`ev-94`) exits 1 with
  `wait_for_deletion: command not found`. Read-only diagnostics also find the port-forward helpers,
  `OP_CATALOG_NAME`, and `WANAKU_BARN_URL` missing. Environment-provided namespace configuration survives.
  These helpers and variables were available in earlier successful steps of the same run.
- **Expected:** Preserve the declared shared session across commands, or explicitly report that it was lost
  and mark dependent tests blocked. Do not silently continue as though its prior state remains available.
- **Separate runner error:** Assessment of test 7.4 also encounters a provider-response decoding error.
  This does not invalidate the separately captured HTTP 503 health evidence, and is not proof that the
  decoding error caused the shell-state loss.
- **Disclosure:** Infrastructure identifiers and raw diagnostic output are omitted intentionally.
