# Health Connect Exporter

Minimal Android app for verifying that Health Connect data can be retrieved from a device. The first milestone reads data locally only; it does not upload to a PC or cloud service.

## What It Reads

- `ActiveCaloriesBurnedRecord` for active calories over the selected period.
- `TotalCaloriesBurnedRecord` if the user grants the optional total calories permission.
- `StepsRecord` if the user grants the optional steps permission.

The app shows the selected local-date period, queried time range, daily rows, aggregate period totals, the number of active calorie records read, and clear unavailable, permission, no-data, and error states. Days without Health Connect data are still shown as explicit zero/no-data rows.

## Period Selection

Use the period buttons to choose `Today`, `Yesterday`, `Last 7 days`, or `Last 30 days`. Tap `Custom date range` to choose a start date and end date with native Android date pickers.

Daily reads use local calendar-day boundaries on the device. A selected date covers midnight at the start of that local date through midnight at the start of the next local date.

## App Updates

The app includes a GitHub Releases based update check. Tap `Check for app update` to fetch the latest release metadata from:

```text
https://github.com/haratak/health-connect-exporter/releases/latest
```

If a newer release with an APK asset is available, tap `Open latest APK download`. Android opens the GitHub-hosted APK URL in the browser/download flow, then the OS asks the user to confirm installation. The app does not silently install updates, bypass Android install prompts, or embed GitHub tokens.

The repository is public so installed APKs can check release metadata and download release assets without credentials. GitHub Actions artifacts are still uploaded for CI convenience, but release APKs are the durable update source because artifacts expire and private artifacts require authentication.

## Sharing

After `Refresh selected period` succeeds, tap `Share latest summary` to send the selected period, queried range, day-by-day active calories, total calories, steps, active calorie record counts, and period totals through Android Sharesheet. Discord can be selected there when it is installed. Tap `Copy latest summary` to place the same day-by-day text on the clipboard.

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

Release builds require the release signing keystore and passwords to be provided through environment variables:

```text
ANDROID_RELEASE_STORE_FILE
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_RELEASE_KEY_PASSWORD
```

With those variables set, run:

```bash
./gradlew assembleRelease
```

The signed release APK is generated at:

```text
app/build/outputs/apk/release/app-release.apk
```

## Install And Test

1. Install the debug APK on an Android device.
2. Open Health Connect Exporter.
3. If prompted, install or update Health Connect.
4. Tap `Request Health Connect permissions`.
5. Grant at least active calories. Total calories and steps are useful but optional.
6. Choose a period preset or custom date range, then tap `Refresh selected period`.

If the app reports no data, confirm that another app has written active calorie data to Health Connect in the queried period.

## GitHub Actions APKs

Every push to `main` runs `.github/workflows/android-apk.yml`, builds `assembleDebug`, and uploads a debug APK artifact named `health-connect-exporter-debug-apk`.

To download it:

1. Open the repository on GitHub.
2. Go to `Actions`.
3. Open the latest `Android APK` workflow run.
4. Download the `health-connect-exporter-debug-apk` artifact.

For release updates, push a version tag such as `v0.2.2`. The same workflow reconstructs the release keystore from GitHub Secrets, runs `assembleRelease`, and creates or updates a GitHub Release with an APK named like `health-connect-exporter-v0.2.2-release.apk`.

Release APKs are signed with the app's release key, not the Android debug key. Android will allow sideloaded updates only when the installed app and the update APK have the same application ID and signing certificate. If a device has a previous debug-signed build installed, uninstall it before installing the release-signed APK; after that, future release APKs signed with the same release key can update in place.

## Privacy

Health Connect data is read on-device and displayed in the app. The app only shares data after an explicit tap on the system Sharesheet or copy action. It does not send health data to a server, write Health Connect data, or store exported health data in the repository.
