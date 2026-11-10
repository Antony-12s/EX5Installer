# Decisions

- 2026-11-10: Phone app that installs APK/APKM/APKS/XAPK on the EX5 over Wi-Fi ADB (`<ip>:5566`, plain TCP, not Android 11 TLS pairing). Own ~300-line ADB client (Adb.java) instead of dadb/Gradle: no dependencies, plain SDK build.sh.
- Firmware 1.3 install verifier: the on-device script saves `verifier_verify_adb_installs`, sets 0, restores in an EXIT trap (same semantics as HUD `Updater.VERIFIER_OFF`). Checkbox on by default; the user taps Install each time.
- The install script runs detached on the car (`setsid -d`), so a dropped phone link cannot stop it between disable and restore. Phone polls `/data/local/tmp/ex5i.log` for END.
- Signed with this PC's debug key for now; switch to a dedicated release key before sharing with other owners (key change breaks updates).
