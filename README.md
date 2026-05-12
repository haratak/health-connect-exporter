# Health Connect Exporter

Minimal Android app for verifying that Health Connect data can be retrieved from a device. The first milestone reads data locally only; it does not upload to a PC or cloud service.

## What It Reads

- `ActiveCaloriesBurnedRecord` for active calories over the last 7 days.
- `TotalCaloriesBurnedRecord` if the user grants the optional total calories permission.
- `StepsRecord` if the user grants the optional steps permission.

The app shows the queried time range, aggregate totals, the number of active calorie records read, and clear unavailable, permission, no-data, and error states.

## App Updates

The app includes a GitHub Releases based update check. Tap `Check for app update` to fetch the latest release metadata from:

```text
https://github.com/haratak/health-connect-exporter/releases/latest
```

If a newer release with an APK asset is available, tap `Open latest APK download`. Android opens the GitHub-hosted APK URL in the browser/download flow, then the OS asks the user to confirm installation. The app does not silently install updates, bypass Android install prompts, or embed GitHub tokens.

The repository is public so installed APKs can check release metadata and download release assets without credentials. GitHub Actions artifacts are still uploaded for CI convenience, but release APKs are the durable update source because artifacts expire and private artifacts require authentication.

## Sharing

After `Refresh last 7 days` succeeds, tap `Share latest summary` to send the displayed range, active calories, total calories, steps, and active calorie record count through Android Sharesheet. Discord can be selected there when it is installed. Tap `Copy latest summary` to place the same text on the clipboard.

Sharing is always user-initiated. The app does not automatically upload, sync, or post Health Connect data.

## Device Requirements

- Android 14 or newer includes Health Connect as a framework module.
- Android 13 or older requires the Health Connect app from Google Play.
- The device must have Health Connect data written by another app before this app can display non-zero values.

## Build Locally

```bash
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install And Test

1. Install the debug APK on an Android device.
2. Open Health Connect Exporter.
3. If prompted, install or update Health Connect.
4. Tap `Request Health Connect permissions`.
5. Grant at least active calories. Total calories and steps are useful but optional.
6. Tap `Refresh last 7 days`.

If the app reports no data, confirm that another app has written active calorie data to Health Connect in the queried period.

## GitHub Actions APK

Every push to `main` runs `.github/workflows/android-apk.yml`, builds `assembleDebug`, and uploads a debug APK artifact named `health-connect-exporter-debug-apk`.

To download it:

1. Open the repository on GitHub.
2. Go to `Actions`.
3. Open the latest `Android APK` workflow run.
4. Download the `health-connect-exporter-debug-apk` artifact.

For release updates, push a version tag such as `v0.2.0`. The same workflow builds the APK and creates or updates a GitHub Release with an APK named `health-connect-exporter-v0.2.0.apk`.

## Privacy

Health Connect data is read on-device and displayed in the app. The app only shares data after an explicit tap on the system Sharesheet or copy action. It does not send health data to a server, write Health Connect data, or store exported health data in the repository.
