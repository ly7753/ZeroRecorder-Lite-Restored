# ZeroRecorder Lite Restored

A headless Android screen recorder that is launched from `adb shell` via `app_process`, instead of a normal app UI.

This project captures:

- Screen video through private display APIs and an OpenGL render path
- Internal audio when the device/ROM allows it
- MP4 output written to `/sdcard/Movies/ZeroRecorder`

## What It Is

This is not a typical tap-to-launch Android app. The APK is pushed to the device, then a Java `main()` entrypoint is executed directly from the shell environment.

That design makes it possible to use system-only APIs such as:

- `SurfaceControl` / `DisplayControl`
- hidden framework context workarounds
- `AudioPolicy` / playback capture internals

Because of this, compatibility depends heavily on Android version, vendor ROM behavior, and shell-level privileges.

## Current State

The restored version currently includes:

- audio capture fallback strategies for Android 13+
- playback-preserving audio capture when the ROM accepts shell attribution
- runtime video fallback from H.265 to H.264
- extra recorder-specific logs for frame delivery, display binding, and encoder startup

Known reality:

- some devices fail on H.265 and need fallback to H.264
- some ROMs are stricter about audio attribution than stock Android
- occasional display/frame delivery issues may still require device-specific tuning

## Project Layout

- `app/src/main/java/com/zero/recorder/MainRecorder.java`
  Main orchestration entrypoint
- `app/src/main/java/com/zero/recorder/audio/`
  Audio capture strategies
- `app/src/main/java/com/zero/recorder/capture/`
  Display binding and rebind logic
- `app/src/main/java/com/zero/recorder/gl/`
  OpenGL frame rendering path
- `app/src/main/java/com/zero/recorder/media/`
  Video encoder and MP4 muxing
- `app/src/main/java/com/zero/recorder/system/`
  Shell/system context workarounds

## Requirements

- Windows host
- Android SDK with `adb`
- Android Studio JBR or another compatible JDK
- A device that allows the shell process to access the required display/audio paths

The included scripts currently assume local paths similar to the author's machine:

- Android SDK: `C:\Users\ly775\AppData\Local\Android\Sdk`
- JBR: `D:\Program Files\Android\Android Studio\jbr`

Update `build.ps1` and `run.ps1` if your environment differs.

## Build

```powershell
.\build.ps1
```

Or directly:

```powershell
.\gradlew.bat assembleDebug
```

## Run

```powershell
.\run.ps1
```

The runner will:

1. locate the Java `main()` entrypoint
2. push the debug APK to the device
3. execute it with `app_process`
4. save logs under `.\logs`

## Output

Recorded files are written on-device to:

```text
/sdcard/Movies/ZeroRecorder/Rec_YYYYMMDD_HHMMSS.mp4
```

## Logs

`run.ps1` writes:

- `logs/console_output_*.txt`
- `logs/logcat_*.txt`
- `logs/logcat_zero_recorder_*.txt`

The filtered logcat file is the best starting point for debugging recorder behavior.

Main tags:

- `ZR.Main`
- `ZR.Display`
- `ZR.GL`

## Notes

- This project uses hidden/private Android APIs.
- It may break across Android releases or OEM ROM updates.
- It is best treated as a shell-side engineering tool, not a production consumer app.
