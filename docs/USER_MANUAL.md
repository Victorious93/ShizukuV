# ShizukuV user manual

> Status: written from the implemented behaviour and the source. **No screen has been checked on a device**, so wording and layout may differ slightly.

## 1. Setup
Open the app. **Status** shows whether the service runs and as what (root, or ADB shell). Start it one way:
- **Root** - allow the root prompt, tap *Start*.
- **Wireless debugging (Android 11+)** - turn on Developer options and Wireless debugging, pair once (*Pair device with pairing code*), tap *Start*.
- **Computer** - run the adb command shown on the Status screen.
Without root the service stops when the device reboots unless boot start works on your phone (see 6). That is an Android limit.

## 2. Approving an app
When an app asks, you see what it wants and choose how long:
- *Until the service stops* - gone after a restart/reboot.  - *For 15 minutes*.  - *Until I revoke it* - survives reboots.
- *Ask me for each operation* - nothing is allowed in advance; each action shows a prompt and one approval covers one action.
- *Deny*.
You can untick capabilities to give less than asked. **Legacy full access** is shown in red: the app can run any command and call system services.
Only grant it to apps you fully trust.

## 3. Apps screen
Search by name or package. Tap an app for its access (capabilities, mode, time left). **Revoke** ends it now, cancels its running work and stops
processes it started through the service. It does not undo things already done.

## 4. Revoke everything
**Status -> Revoke all access and stop managed work** (also in Diagnostics). It ends every authorization, cancels all queued/running controlled
operations and stops processes and services apps started through the service. It cannot stop a background process a trusted shell detached.

## 5. Activity and Diagnostics
*Activity* lists authorization changes, service lifecycle and controlled operations (time, app, capability, request id, duration, outcome).
It never stores secrets, pairing codes, file contents or command arguments, and **cannot see what legacy full-access apps do**. Choose how much to keep
or clear it. *Export redacted report* opens the share sheet; nothing is sent automatically.
*Diagnostics* shows the service state, mode, restrictions, the **last start result with the reason and what to do**, active operations, and *Retry start*.

## 6. Start on boot
Settings -> *Start on boot*. For wireless-debugging boot start the app may need to switch settings. Each is **off** until you enable it:
Wireless debugging, USB debugging, ADB authorization timeout, and *Restore the previous values after the service started* (it never overwrites a
value you changed yourself). If a start fails, Diagnostics names the reason: missing authorization (grant `WRITE_SECURE_SETTINGS` once),
pairing failure, discovery timeout, port unavailable, root denied, service not answering, or an OEM restriction (exempt the app from battery limits).

## 7. Terminal (`rish`)
*Use in terminal apps* exports `rish` and `rish_shizuku.dex`; in the terminal `export RISH_APPLICATION_ID=<terminal package>`. The terminal app must be
approved with **Legacy full access**. `rish` prints one line naming the privilege (root or ADB) - `RISH_QUIET=1` hides it. VictorSuite ships
`bridge-rish-doctor` to diagnose this.

## 8. Settings profiles
Settings -> *Import and apply a profile*. A profile is a small JSON file of setting values (see *Export example profile*). You see each change and the
required capability first; supported settings only. Previous values are saved: *Undo last applied profile* restores them, and warns if a value was
changed after applying. Profiles never contain keys or authorizations.

## 9. Troubleshooting
| Problem | Do |
|---|---|
| Service not running | Diagnostics -> last start reason; *Retry start* |
| App says denied / expired / revoked | approve again; check the duration you chose |
| App cannot read a folder | it was not in the approved folders, or it is a protected location (other apps' private data is never reachable) |
| Works on root, not on ADB | ADB (shell) has fewer rights; see Status for the mode |
| Boot start never works | Android limit without root; use the computer or root route |
