---
name: app-ui-test
description: Smoke-test an app the agent built: install, launch, navigate screens, capture screenshots, report what worked and what failed.
category: testing
---

# App UI Test

Use this skill after building/installing an app to verify it works on the device.

## Preconditions
- Device control enabled (see `device-control` skill).
- The APK installed (via `install_apk` or already present).

## Procedure
1. Launch the app with `device_open_app` (package name).
2. `device_wait` 2000ms, then `device_screenshot` + `device_ui_tree`.
3. Walk the main screens: tap primary navigation, wait, screenshot, check for crashes/blank screens.
4. Try a happy-path flow (e.g., create an item, submit a form) without entering real secrets.
5. Capture a screenshot per screen visited.
6. Report: screens visited, actions that succeeded, failures/crashes with the last screenshot, and suggested fixes.

## Constraints
- Do not enter real passwords/OTPs; use test data only.
- Stop on the first crash and capture the UI tree for diagnosis.
- Respect device-control step/duration limits.
