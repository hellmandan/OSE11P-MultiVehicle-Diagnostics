# UniversalDrive — Android Studio project

Bluetooth ELM327 OBD-II diagnostics starter app.

## Included
- Bluetooth Classic SPP connection to paired ELM327 adapters.
- Automatic OBD protocol selection.
- Standard OBD-II stored DTC read (Mode 03) and clear (Mode 04).
- Live RPM, speed, coolant temperature and control-module voltage where supported.
- Readiness monitor request and read-only CAN monitor request.
- Vehicle manufacturer coverage catalogue and diagnostic service reference.
- Remote-start compatibility framework for documented, authenticated remote-start controllers.

## Build
1. Open this folder in Android Studio.
2. Install Android SDK Platform 35.
3. Sync Gradle.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).
5. Pair an ELM327 in Android Bluetooth settings and connect in the app.

## Safety and compatibility
Generic OBD-II does not expose every vehicle module or every live parameter. Enhanced diagnostics require validated model/year/market/ECU definitions. ELM327 adapter firmware varies; this project supports Bluetooth Classic SPP, not BLE.

There is no universal OBD/CAN remote-start command or universal database of valid start codes. Remote start must use a compatible authenticated controller with independent Park/Neutral, brake, bonnet and immobiliser interlocks. This project intentionally excludes guessed ignition/starter injection, immobiliser bypass frames and security-key algorithms.

This is a starter project, not a certified professional scan tool. The APK must be built in Android Studio.
