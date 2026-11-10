# EX5 Installer

Android phone app for the Geely EX5 head unit over Wi-Fi ADB (`<car-ip>:5566`).

- Install `.apk`, `.apkm`, `.apks` and `.xapk` files on the car. Split apps install as one session.
- Firmware 1.3: optional switch that turns `verifier_verify_adb_installs` off for one install and restores the old value afterwards. The install script runs on the car, so a dropped phone link cannot leave it off.
- View the car's main display (display 0) on the phone and tap to control it (scrcpy 5.0.1 server).

Plain TCP ADB only (`adb tcpip`). Android 11 wireless-debugging pairing codes are not supported.

## Build

Needs Android SDK build-tools 36.0.0, platform android-36 and JDK 21. No Gradle.

```
sh build.sh            # unit tests + out/ex5-installer.apk (signed with ~/.android/debug.keystore)
EX5_HOST=192.168.1.158 EX5_PORT=5566 sh test/run.sh                  # live: exec + push check
EX5_HOST=... EX5_SCREEN=1 sh test/run.sh                             # live: screen stream check
```

## Third party

`assets/scrcpy-server.jar` is the scrcpy 5.0.1 server by Genymobile, Apache License 2.0
(`third_party/scrcpy-LICENSE`), unmodified.
