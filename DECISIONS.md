# Decisions

- 2026-11-10: Phone app that installs APK/APKM/APKS/XAPK on the EX5 over Wi-Fi ADB (`<ip>:5566`, plain TCP, not Android 11 TLS pairing). Own ~300-line ADB client (Adb.java) instead of dadb/Gradle: no dependencies, plain SDK build.sh.
- Firmware 1.3 install verifier: the on-device script saves `verifier_verify_adb_installs`, sets 0, restores in an EXIT trap (same semantics as HUD `Updater.VERIFIER_OFF`). Checkbox on by default; the user taps Install each time.
- The install script runs detached on the car (`setsid -d`), so a dropped phone link cannot stop it between disable and restore. Phone polls `/data/local/tmp/ex5i.log` for END.
- Signed with this PC's debug key for now; switch to a dedicated release key before sharing with other owners (key change breaks updates).
- 2026-11-10: Release key made (`/mnt/work/ex5installer-release-keystore/`, cert SHA-256 8867b1f4...5873, outside the repo). Releases from 1.1 on use it; an install of the debug-signed 1.0 must be uninstalled once. Code under GPLv3 (changed from MIT 2026-11-10 at the user's request, before any third-party contribution; sole author so relicensable); scrcpy-server.jar stays Apache-2.0, which is GPLv3-compatible. The v1.1 release tag was published under MIT and stays MIT for whoever already has it; v1.2 onward is GPLv3.
- 2026-11-10: Screen view = scrcpy 5.0.1 server (same jar as Geely Car Viewer, sha256 764eb6f7...). Video only plus touch control on display 0. Long side capped at 1280 (max_size): 2560 px crashed the decoder on the test phone.
- Decoder time = our own frame counter, not device pts: the device pts made the phone codec abort (ubsan mul-overflow).
- Adb is now multi-channel with a reader thread, so the screen stream and install run on one connection.
