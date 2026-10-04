# M4.2 evidence, 2026-10-04

Read ../../android-windows-m4-2.md for setup, exact scope, failures, and acceptance assessment.

- `stream-seven-*`, `*-rates.csv`, `memory-seven-*`, and `*-memory-summary.json`: seven snapshots per mode, six adjacent rate intervals, and contemporaneous memory.
- `wifi-full-confirmed.csv`, `android-stop-*.json`, visual confirmation records: physical transitions and user statements. No Wi-Fi-loss screenshot was obtained; the report explains the watcher failure.
- `desktop-three-fixed-cycles.csv`: successful normal shutdown, listener release and waiting reopen.
- `desktop-live-exit*`, `obs-live-producer-exit.png`: exit while streaming, process/port release, then black source output. The exit and subsequent recovery were visually confirmed by the user; `desktop-final-live-restart-stop.csv` and confirmation records preserve the final restart/consumer reopen/Stop.
- `ipc-auth-failure.csv`, `desktop-close-failure.*`, old/final module paths: failures retained before corrective deployment.
- `camera-host-*-final.json`: loaded source hash, actual service identity and enabled FrameServer group.
- `obs-camera-validation-extract.txt`: real OBS camera settings and versions.
- `cli-physical-sanitized.txt`, first seven streaming samples, Stop observations: physical 1080p CLI decode and cleanup; credentials excluded.
- `receiver-final-selftest.txt`, `native-ipc-final.txt`, `decoder-final-smoke.txt`, `ctest-final.txt`, `desktop-final-build.txt`: final automated check outputs.

Black neutral PNGs contain no live camera content. Live phone screenshots and raw diagnostic helpers remain local.
