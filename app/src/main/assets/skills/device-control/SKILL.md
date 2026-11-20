---
name: device-control
description: Control Android apps on the device via shell (Shizuku/Wireless ADB/Root): screenshot, UI tree, tap, swipe, type, keys, open app, wait.
category: device
---

# Device Control Agent

Use this skill when the user asks the agent to operate Android apps on the device (not the browser). Loop: observe → decide → act → verify.

## Preconditions
- `device_control_enabled` is true in Settings (disabled by default; the user must enable it).
- A working shell tier (Shizuku / Wireless ADB / Root) is available; otherwise tools return a clear error with setup guidance.
- The target app is not on the blocked list unless the user explicitly approves an override.

## Safety (mandatory)
- Never type passwords, OTP codes, or other secrets. `device_type_text` refuses numeric codes and secret-like strings.
- Never control while the screen is locked; check first.
- Respect blocked packages (banks, payments, password managers, system security) — require a separate explicit approval to override.
- Never uninstall apps, wipe data, factory reset, or run anything the shell policy blocks.
- Stop immediately on the user's Stop request; track steps/duration limits.
- Screenshots are not persisted after the session unless the user asks; never leak secrets in screenshots.

## Loop
1. **Observe**: `device_screenshot` (if vision supported) and/or `device_ui_tree`.
2. **Decide**: identify the target element and its bounds/coordinates.
3. **Act**: `device_tap` / `device_swipe` / `device_type_text` / `device_key` / `device_open_app` / `device_wait`.
4. **Verify**: re-observe; if the expected state is not reached, retry or report failure.

## Tools
- `device_screenshot`, `device_ui_tree`, `device_tap`, `device_swipe`, `device_type_text`, `device_key`, `device_open_app`, `device_wait`.

## Tips
- Prefer `device_ui_tree` to find exact tap coordinates from bounds (center of bounds).
- Use `device_wait` after navigation actions before re-observing.
- If vision is unsupported by the model, rely on the text UI tree only.
