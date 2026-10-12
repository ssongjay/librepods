## Root Requirement

LibrePods *may* require root depending on your device/OS and what features you want access to:

- Features requiring the VendorID hook ([the features marked with an asterisk here](https://github.com/kavishdevar/librepods#key-features)) will always require root regardless of your device/OS.
- On **ColorOS/OxygenOS 16 and realme UI 7.0** and **Pixel devices on Android 16 QPR3** (with the latest Google Play system update), LibrePods does not need root for most features.
- On other devices, LibrePods needs root because of a bug in the Android Bluetooth stack Fluoride/non-compliance of Apple with Bluetooth standards. You must have Xposed installed for the app to workaround this bug and connect to AirPods. [This issue is being tracked here](https://issuetracker.google.com/issues/371713238). **Please do not comment on the issue thread.** The issue has already been resolved and should be available in **Android 17** for all devices.

> [!IMPORTANT]
> This workaround with Xposed is not guaranteed to work on all devices.


## Installation

### Google Play Store

If you are using a supported device/OS combination, you can install LibrePods from the Google Play Store. You can use the VendorID hook features with root even from the Play Store version.

<a href="https://play.google.com/store/apps/details?id=me.kavishdevar.librepods"><img width="170" alt="GetItOnGooglePlay_Badge_Web_color_English" src="https://github.com/user-attachments/assets/2948308f-af92-443f-94d9-ee381c3a6ccc"/></a>

### GitHub Releases

If you need xposed because of the [root requirement](#root-requirement), you will have to use the apk/zip from the [GitHub releases](https://github.com/kavishdevar/librepods/releases/latest).

### As a system app (root module)

If you want LibrePods to have privileged Bluetooth permissions to 
- show battery status in the system settings and widgets
- show AirPods icon in the system settings (xposed is also currently required for this)
- switch audio to phone speakers when you are not wearing your AirPods

you can install the root module. This is optional and only provides extra features, but it is not required for the app to work.

> [!IMPORTANT]
> When using the root module, do not install the Play Store version. There might be issues because of the signature mismatch between the Play Store version and the root module.

## Nightly/Development Builds

Want to try the latest features before they're officially released? You can grab nightly builds from the [latest nightly release](https://github.com/kavishdevar/librepods/releases?q=nightly).

> [!WARNING]
> These builds are automatically generated from the latest code and may contain new features and bug fixes that haven't been included in a stable release yet. However, please note that they may also be less stable than official releases, so use them at your own risk.

## Screenshots

|                                                                                 |                                            |                                                                      |
| ------------------------------------------------------------------------------- | ------------------------------------------ | -------------------------------------------------------------------- |
| ![Settings 1](./imgs/settings-1.png)                                            | ![Settings 2](./imgs/settings-2.png)       | ![Head Tracking and Gestures](./imgs/head-tracking-and-gestures.png) |
| ![Long Press Configuration](./imgs/long-press.png)                              | ![Customizations 1](./imgs/customizations-1.png)                                | ![accessibility](./imgs/accessibility.png) |
| ![transparency](./imgs/transparency.png)                                        | ![hearing-aid](./imgs/hearing-aid.png)     | ![hearing-test](./imgs/hearing-test.png)   |
| ![hearing-aid-adjustments](./imgs/hearing-aid-adjustments.png)                  | ![Battery Notification and QS Tile for NC Mode](./imgs/notification-and-qs.png) | ![Widget](./imgs/widget.png)               |


here's a very unprofessional demo video

https://github.com/user-attachments/assets/43911243-0576-4093-8c55-89c1db5ea533

### Troubleshooting steps for common errors
- Ensure the correct scope is set in LSPosed/Vector.
- Ensure there is no root-hiding module preventing the hook from loading on the Bluetooth app.
- Restart your phone after confirming the scope.

### LibrePods My personal updates

The personal build keeps the package `me.kavishdevar.librepods.inje` so it can
update the existing Inje APK without removing app settings. Its display name is
LibrePods My. Build it with `assembleFossDebug`; the distributed APK is explicitly
non-debuggable even though this build task retains its upstream name.

Keep the existing signing keystore when building later versions. A new signing
key cannot update an installed personal build. Never commit the keystore or its
credentials. Increment `versionCode` for each published update. Starting with internal
build 69, use a separate display version (`0.1.0`, then e.g. `0.1.1` or `0.2.0`).
The app version name and GitHub release title must agree: use the exact release
title `LibrePods My <major.minor.patch>`, for example `LibrePods My 0.1.0`.
The updater shows that display version and compares only the numeric internal
tag when deciding whether an update is newer. Keep `my-v<versionCode>` tags so
installed v66–v68 updaters continue to recognize later releases. Older installed
apps show the internal number once; after upgrading they show the display version.

Publish to `ssongjay/librepods` as a non-prerelease GitHub release with the tag
`my-v<versionCode>` and the exact asset name `LibrePods-My.apk`. Point the release
at the commit that produced the APK. Verify its signature, package/version, and
GitHub asset SHA-256 before publishing. The app checks that release only when the
user presses the update button in app settings. Download opens the exact APK URL
in the browser; the user opens the downloaded APK and Android confirms installation.
The app does not request permission to install packages or verify the downloaded
file itself. Package updates still require a matching installed signing certificate,
which Android enforces.

Run `assembleFossDebug lintFossDebug`, `verification/run_personal_update_checks.py`,
and `verification/run_wear_checks.py` before publication. Set `JAVA_HOME` and
`GRADLE_USER_HOME` for the standalone checks. The update checks use the existing
Kotlin compiler cache and introduce no new app dependencies.

### Personal build permission audit (v68)

The personal `fossDebug` APK removes five permissions from v67:

- `REQUEST_INSTALL_PACKAGES`: replaced with an explicit browser download action.
- `BLUETOOTH_ADVERTISE`: no BLE advertiser is implemented.
- `BLUETOOTH` and `BLUETOOTH_ADMIN`: the APK requires Android 13+, where the
  implemented Bluetooth features use `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN`.
- `com.android.vending.BILLING`: the FOSS provider opens a GitHub Sponsors link.

Bluetooth scan/connect, foreground monitoring, notifications, restart after boot,
audio settings, and Internet for user-initiated update checks remain implemented.
Phone state and answering permissions remain optional for call detection and head
gestures. Drawing over other apps remains optional for the connection popup and
island UI. The app requests only CONNECT and SCAN in its Bluetooth onboarding.

The system-only `BLUETOOTH_PRIVILEGED`, `MODIFY_PHONE_STATE`, `LOCAL_MAC_ADDRESS`,
and `INTERACT_ACROSS_USERS` declarations remain because they support original
privileged/root-module features. Ordinary installs cannot receive them, and the
existing permission checks and fallback paths remain unchanged.

The removals use variant manifest overlays. The Play build retains billing
support. The personal package ID and signing key must remain unchanged.

This reduces unused capabilities; it is not a claim that the APK is malware-free
or that Samsung's security classification will clear. Do not disable protection
to install an APK that is blocked. A continuing block needs vendor review.

### A few notes

- Due to recent AirPods' firmware upgrades, you must enable `Off listening mode` to switch to `Off`. This is because in this mode, loud sounds are not reduced.

- When renaming your AirPods through the app, you'll need to re-pair them with your phone for the name change to take effect. This is a limitation of how Bluetooth device naming works on Android.
