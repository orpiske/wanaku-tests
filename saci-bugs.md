# Saci findings from the operator plan

## 1. Parent units re-present completed procedure invocations without their evidence

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
- **Source verification** (local Midnight Saci checkout):
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
