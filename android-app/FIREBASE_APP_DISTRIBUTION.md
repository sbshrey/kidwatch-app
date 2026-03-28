# Firebase App Distribution Setup

This repo is now wired for Firebase App Distribution through Gradle so you can share Android builds without a Play Console account.

## What this supports

- `appDistributionUploadDebug` for the current debug APK
- `appDistributionUploadRelease` if you later choose to sign and distribute a release APK
- Local-only tester lists, groups, and service-account credentials

## One-time Firebase Console setup

1. Create or open a Firebase project.
2. Add an Android app with package name `com.kidwatch.monitor`.
3. Download `google-services.json`.
4. Put it at `android-app/app/google-services.json`.
5. In Firebase App Distribution, create a tester group if you want to share by group name.

## Local files

These files are intentionally ignored by git:

- `android-app/app/google-services.json`
- `android-app/firebase-appdistribution/testers.txt`
- `android-app/firebase-appdistribution/groups.txt`
- `android-app/firebase-appdistribution/service-account.json`

Copy from the examples if useful:

- `android-app/firebase-appdistribution/testers.example.txt`
- `android-app/firebase-appdistribution/groups.example.txt`

## local.properties keys

Add these to `android-app/local.properties` as needed:

```properties
sdk.dir=/Users/your-user/Library/Android/sdk

FIREBASE_APP_ID=1:1234567890:android:abcdef123456
FIREBASE_APP_DIST_GROUPS=trusted-testers
FIREBASE_APP_DIST_TESTERS_FILE=firebase-appdistribution/testers.txt
FIREBASE_APP_DIST_SERVICE_CREDENTIALS_FILE=firebase-appdistribution/service-account.json
```

Notes:

- `FIREBASE_APP_ID` is optional if your local `google-services.json` already matches the app.
- `FIREBASE_APP_DIST_GROUPS` is optional if you want to upload to tester emails instead.
- `FIREBASE_APP_DIST_TESTERS_FILE` defaults to `firebase-appdistribution/testers.txt`.
- `FIREBASE_APP_DIST_SERVICE_CREDENTIALS_FILE` is optional if you authenticate another supported way.

## Upload commands

Build only:

```bash
ANDROID_HOME=/Users/shreybaheti/Library/Android/sdk ANDROID_SDK_ROOT=/Users/shreybaheti/Library/Android/sdk ./gradlew assembleDebug
```

Build and upload debug:

```bash
ANDROID_HOME=/Users/shreybaheti/Library/Android/sdk ANDROID_SDK_ROOT=/Users/shreybaheti/Library/Android/sdk ./gradlew appDistributionUploadDebug
```

Build and upload release:

```bash
ANDROID_HOME=/Users/shreybaheti/Library/Android/sdk ANDROID_SDK_ROOT=/Users/shreybaheti/Library/Android/sdk ./gradlew appDistributionUploadRelease
```

## Recommended tester flow

- Use Firebase App Distribution as the default remote-sharing method.
- Keep `adb install` as fallback for testers whose phones still block non-Play installs.
- Warn testers that Play Protect may still flag non-Play installs, especially when the app requests sensitive access like Accessibility.
