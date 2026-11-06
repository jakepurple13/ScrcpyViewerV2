# Claude Device Manager (CDM) integration

Scrcpy Vwr takes turns on Android devices through CDM (a local HTTP server that shares devices between Claude Code
sessions and other tools). When CDM is not running, the app behaves exactly as before.

Mockups: https://claude.ai/artifact/NCAzuiepTUhSqMG6KYJb1V. Picks: 1A, 2A, 3A without the "Don't use" chip, 4A.

## Behavior

- **Hold per mirror window.** Opening a mirror window leases the device; closing it releases the lease. The session's
  connection starter waits for the lease before it touches the device (`adb push`, tunnel, server).
- **Queue (1A).** While CDM says `QUEUED <n>`, the mirror window shows "Waiting for <model>", the queue position and
  Cancel. Cancel or closing the window closes the acquire connection, which leaves the queue. `TIMEOUT` re-calls
  acquire.
- **Hold lost (2A).** A renew every 60 s; `NONE` (or a renew that finds the hold gone) stops the session (a recording
  is finished first) and shows "Device taken back" with Wait again (re-queues) and Close.
- **Rejected.** `BLOCKED`, `REMOVED` or HTTP 400 (unknown, don't-use) fail the session with CDM's reason.
- **Device list chips (3A).** Live from `GET /cdm/events`: "In use · <holder>" when another holder has it,
  "Mirroring here" when this app does, "Free" otherwise. No chip for don't-use. No chips when CDM is closed.
- **kill-server (4A).** While CDM runs: the Restart adb button is disabled with a tooltip, the Wi-Fi snackbar's
  Restart adb action is hidden, automatic restarts are skipped, and the updater skips `adb kill-server`.
- **Wi-Fi switch.** `tcpip` runs under a short lease (shared with a mirror lease of the same device, so it never
  queues behind this app's own window).

## Holder IDs

`<app>:<slot>`. `<app>` is `scrcpy-vwr` for the first running instance and `scrcpy-vwr-<n>` for the n-th concurrent
instance (an OS file lock in the data dir picks the lowest free n; a killed process frees its lock). `<slot>` is the
device's hardware serial (`ro.serialno`, same over USB and Wi-Fi) or the adb serial, reduced to `[A-Za-z0-9._-]`.
Stable across launches, one Android device per holder, distinct per concurrent instance.

## Crash safety

- Startup: read `GET /cdm/devices` and release every hold whose holder starts with `<app>:` (left by a killed run).
- Normal close and window close release. A JVM shutdown hook releases every hold still out.

## CDM discovery

Port from `~/.claude-device-manager/port`, host 127.0.0.1, checked with `GET /cdm/ping` == `cdm-ok` on each acquire.
Absent or not answering: run without a lease, no error. The device-list stream reconnects quietly every 10 s.

## Components

- `cdm/CdmClient` — HTTP: ping, acquire (line-streamed, cancellable), renew, release, devices, events.
- `cdm/CdmHolds` — leases per device: holder naming, ref counts, queue/lost state flows, renew loop, startup cleanup,
  shutdown hook.
- `cdm/CdmInstance` — instance number from a file lock.
- `cdm/CdmDevices` — the live `/cdm/events` snapshot for the device list and the "CDM running" flag.
- Gated `ConnectionStarter`; `MirrorController` leases on open, releases on close, `requeue` for Wait again.
