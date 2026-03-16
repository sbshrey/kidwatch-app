# Android Validation Rule

## Scope
This rule applies when making changes in Android project files (for example under `android-app/`).

## Required After Every Change
1. Build the app (`assembleDebug` or equivalent).
2. Confirm a physical device is connected with `adb devices`.
3. Install and run the updated debug build on the connected device.
4. Validate the changed behavior directly on-device (not emulator-only).
5. Report validation result in the final update (what was tested and outcome).

## If Device Is Not Available
- Stop before claiming validation is complete.
- Clearly report that on-device validation could not be performed.
- List the exact command(s) and verification step still pending.
