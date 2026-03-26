# <img alt="Outline Manager Logo" src="docs/resources/logo_manager.png" title="Outline Manager" width="32">&nbsp;&nbsp;Outline ST&nbsp;&nbsp;<img alt="Outline Client Logo" src="docs/resources/logo_client.png" title="Outline Client" width="32">

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

### Bug Fixes
- Fixed Service ANR when updating APK with active VPN connection
- Fixed ANR during Go backend and Sentry SDK initialization
- Fixed incomplete app list on Android 11+
- Fixed ANR when opening split tunnel app list

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


## Community and Support

Interested in **contributing to Outline?** See our [Contributing Guide](CONTRIBUTING.md) for more information.

See [AGENTS.md](./AGENTS.md) for AI agent and developer guidance.

You can also **join the Outline Community** by signing up for the [IFF Mattermost](https://wiki.digitalrights.community/index.php?title=IFF_Mattermost)!

For customer support and to **contact us directly**, go to https://support.getoutline.org.
