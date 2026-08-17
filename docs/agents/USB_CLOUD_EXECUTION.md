# USB cloud-first execution

## Goal

Keep code construction, deterministic review, and CI verification in remote Git branches. Use the local AgentDock/Android/DAC environment only when a task is explicitly `HARDWARE_REQUIRED`.

## Default lane

For any USB task that does **not** require a physical Android device, DAC, USB permission, USBFS/kernel behavior, AudioTrack/real audio output, screen/lock state, reconnect/soak, or other machine-specific state:

1. Start from an exact accepted SHA.
2. Create or continue a branch under `automation/usb/**`.
3. Perform implementation/review/planning against that branch.
4. Push each recoverable checkpoint; Git is the durable worker journal.
5. Require the `USB cloud gate` workflow to finish GREEN before accepting the branch as cloud-ready.
6. P4 reviews the exact pushed SHA and CI evidence, not an uncommitted local worktree.
7. P6 plans from the accepted exact SHA.

Do not use the local USB worktree as the default construction workspace.

## Branch contract

Recommended names:

- `automation/usb/r2-direct-a19-a33-a36`
- `automation/usb/r2-rebuild-a20-a21-a31`
- `automation/usb/r2-recovery-a22-a23`

A tranche branch must have one exact accepted base SHA. Scope-expanding work starts a new branch/tranche instead of silently widening the current one.

## Cloud gate

`.github/workflows/usb-cloud-gate.yml` runs on every push to `automation/usb/**`.

The gate currently verifies:

- clean exact checkout and recursive submodules;
- `git diff --check`;
- Debug + Perf Kotlin compile;
- stable USB PCM protocol/coordinator/structure regression;
- optional tranche-specific test patterns from `.github/usb-cloud-extra-tests.txt`;
- checkout cleanliness after the established runner-local `usbprototype` fixture workaround.

The workflow always uploads `USB_CLOUD_CHECKPOINT.json` with branch, exact SHA, cloud gate status, and `local_hardware_status=NOT_RUN`.

A GREEN cloud checkpoint is **not** a hardware qualification result.

## HARDWARE_REQUIRED boundary

Mark a task `HARDWARE_REQUIRED` only when its acceptance depends on state unavailable to GitHub-hosted runners, including:

- physical DAC attach/detach/reconnect;
- Android USB permission or real device lifecycle;
- USBFS/kernel/native transport behavior;
- physical carrier zero/reset/release observation;
- real PCM/DoP/native DSD audio output;
- actual sample-rate/device capability behavior;
- screen-off/lock/background behavior;
- long soak or physical fault recovery.

At that point stop cloud construction and hand off an **exact pushed SHA** plus a finite hardware test matrix.

## Local handoff

The local lane must:

1. preserve any unrelated/manual dirty worktree;
2. fetch the exact cloud SHA into an isolated worktree/checkout;
3. verify SHA and cloud gate GREEN;
4. run only the hardware matrix that cannot be proven in cloud;
5. record hardware result against the exact SHA;
6. never silently modify the cloud-reviewed SHA during qualification.

If hardware reveals a code defect, return to a new cloud commit/branch iteration and repeat cloud review before another hardware run.

## Failure/recovery model

Durable state is primarily:

- remote branch;
- exact SHA;
- CI run/checkpoint;
- specialist-master stage/task.

ChatGPT conversation URLs and AgentDock journals are recovery aids, not the source of truth for code state.

## Current bootstrap

This workflow bootstrap starts from accepted USB checkpoint:

`2b4779ba4c4d20b39de5b40d489c9a971180b8d9`

The existing local USB worktree may continue manual work independently; cloud bootstrap must not reset, clean, stash, or otherwise alter it.
