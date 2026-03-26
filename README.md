## Outline ST

Modified [Outline](https://github.com/Jigsaw-Code/outline-apps) client for Android with **per-app split tunneling** (app-level routing).

> **Can be installed alongside the original Outline app** — uses a separate application ID (`org.outline.android.client.st`).

### Features
- **Per-app VPN bypass** — choose which apps should bypass the VPN tunnel
  - Search and filter installed apps
  - Toggle system apps visibility
  - Select all / deselect all
  - Settings auto-save on exit
- **App Logs viewer** — built-in log viewer for diagnostics
- **ANR watchdog** — monitors main thread responsiveness
- **Automatic upstream sync** — GitHub Actions checks for Outline updates every 12 hours

### Installation
1. Download `outline-st-v0.1.0.apk` below
2. Install on Android device (requires Android 10+)
3. The app appears as **"Outline ST"** in the launcher — separate from the original Outline
4. Add your Outline server key
5. Open the navigation menu → **Apps to Bypass VPN** to configure split tunneling

### Coexistence with Original Outline
This fork uses `org.outline.android.client.st` as its application ID, so it can be installed side-by-side with the original Outline app. They do not share data or interfere with each other.

### Attribution
Based on [Outline VPN](https://github.com/Jigsaw-Code/outline-apps) by [Jigsaw / Google](https://jigsaw.google.com/).
Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

### Disclaimer
This software is provided as-is for research and personal use. Use it responsibly and in accordance with the laws of your jurisdiction
