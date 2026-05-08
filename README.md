# FASTADB OTG

FASTADB OTG is an Android app that provides a lightweight ADB and Fastboot console over USB OTG. It is designed for cases where you want to send basic ADB or Fastboot commands from one Android device to another Android device without using a desktop computer.

The app detects USB devices exposing Android ADB or Fastboot interfaces, requests USB permission, opens a USB bulk transport session, and provides a simple terminal-style interface for command execution.

## Features

- Detects ADB and Fastboot USB interfaces through Android USB host mode.
- Supports ADB authentication using an app-generated RSA key stored in Android Keystore.
- Provides a terminal interface for sending ADB shell/service commands and Fastboot commands.
- Displays basic device information, including product, serial number, bootloader version, current slot, build information, and connection mode when available.
- Includes quick actions for common workflows:
  - `adb reboot bootloader`
  - `fastboot reboot-bootloader`
  - `fastboot oem set-gpu-preemption 0 androidboot.selinux=permissive` followed by `fastboot continue`
- Built with Kotlin and Jetpack Compose.

## Requirements

### Host device

- Android 13 or newer.
- USB OTG / USB host support.
- A USB-C cable or OTG adapter that allows the host Android device to connect to the target device.

### Target device

For ADB mode:

- USB debugging must be enabled.
- The target device must authorize the FASTADB host key when the Android debugging prompt appears.

For Fastboot mode:

- The target device must already be in bootloader / Fastboot mode.
- The device must expose a standard Fastboot USB interface.

## Usage

1. Install and open FASTADB OTG on the host Android device.
2. Connect the target device through USB OTG.
3. Grant USB permission when prompted by Android.
4. For ADB mode, confirm the USB debugging authorization prompt on the target device.
5. Use the terminal input to send commands.

Example ADB commands:

```bash
adb shell getprop ro.product.model
adb reboot bootloader
shell:getprop ro.build.display.id
```

Example Fastboot commands:

```bash
fastboot getvar product
fastboot getvar current-slot
fastboot reboot-bootloader
fastboot continue
```

## Supported command behavior

ADB commands are normalized before execution:

- `adb shell <command>` is sent as an ADB shell service.
- `adb reboot [target]` is sent as an ADB reboot service.
- `shell:<command>` and `reboot:<target>` can be sent as raw ADB services.
- Other non-empty ADB input is treated as a shell command.

Fastboot commands are normalized before execution:

- `fastboot getvar <name>` is converted to `getvar:<name>`.
- Other non-empty Fastboot input is sent as a raw Fastboot command body.

## Current limitations

- Only one connected USB device is handled at a time.
- Fastboot DATA-phase commands are not supported yet, so flashing, booting images, sideloading, and similar operations are not implemented.
- ADB support is focused on shell, reboot, and raw service commands. It does not currently implement file transfer, port forwarding, install, logcat streaming, or full desktop-ADB parity.
- Device compatibility depends on the target device exposing standard ADB or Fastboot USB interfaces.

## Build from source

Clone the repository and build with Gradle:

```bash
git clone https://github.com/byemaxx/FASTADB.git
cd FASTADB
./gradlew assembleDebug
```

The debug APK will be generated under:

```text
app/build/outputs/apk/debug/
```

## Release signing

Release builds can be signed either through a local `keystore.properties` file or through environment variables.

### Local signing configuration

Create a `keystore.properties` file in the project root:

```properties
storeFile=/path/to/release.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

Then build the release APK:

```bash
./gradlew assembleRelease
```

### Environment variables

Alternatively, provide the following environment variables:

```bash
ANDROID_KEYSTORE_PATH=/path/to/release.keystore
ANDROID_KEYSTORE_PASSWORD=your_store_password
ANDROID_KEY_ALIAS=your_key_alias
ANDROID_KEY_PASSWORD=your_key_password
```

## GitHub Actions release workflow

The repository includes a manual GitHub Actions workflow for building and publishing a signed release APK. The workflow expects these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow builds `assembleRelease`, uploads the signed APK as a workflow artifact, and attaches it to the selected GitHub Release tag.

## Project structure

```text
app/src/main/java/com/byemaxx/fastadb/
├── MainActivity.kt          # App entry point and USB intent handling
├── FastAdbScreen.kt         # Jetpack Compose UI
├── FastAdbViewModel.kt      # Device detection, state management, and command dispatch
└── UsbSessions.kt           # USB transport, ADB session, Fastboot session, and command parsing
```

## Security notes

ADB and Fastboot commands can reboot, modify, or otherwise affect the target device. Use the app only with devices you own or are authorized to service.

FASTADB generates its own ADB RSA key pair and stores it through Android Keystore. If the target device no longer trusts the host, revoke USB debugging authorizations on the target device and authorize the app again.

## License

No license file is currently included in this repository.
