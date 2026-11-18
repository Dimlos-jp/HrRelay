# HR Relay

A small Android app that subscribes to a BLE Heart Rate Measurement characteristic
(service 0x180D / characteristic 0x2A37) and relays each reading as a broadcast intent.
Use MacroDroid's "Intent Received" trigger to handle the data; all thresholds, notifications
and SMS are done in your macros.

Written for a Galaxy Watch broadcasting heart rate over BLE with a watch-side app
(e.g. "Heart for Bluetooth"). It works with any BLE device that exposes the standard Heart Rate service.

## Build
Open the folder in Android Studio and run (or Build > Build APK).
The Gradle wrapper jar is not included. If sync fails, run `gradle wrapper` once.

A debug-signed APK is also available under Releases.

## Usage
1. Make sure the watch is broadcasting heart rate. Galaxy Watch: start a watch-side app such as "Heart for Bluetooth". HUAWEI WATCH GT 4: start heart rate sharing from the watch's sharing menu. Amazfit GTR 4: start the watch's built-in "heart rate push" app. Garmin vivoactive 5: turn on the heart rate broadcast mode in the controls menu. Disconnect other BLE clients (e.g. nRF Connect) first.
2. Find the watch's MAC address. Use nRF Connect's Scanner tab and look for the device name shown on the watch (its sharing screen, if it has one). On the Galaxy Watch Ultra, the Amazfit GTR 4 and the Garmin vivoactive 5 the address was the same as the one in the paired devices list; on the HUAWEI WATCH GT 4 it was different. On the GT 4, the GTR 4 and the vivoactive 5 the address stayed the same after stopping and restarting the sharing.
3. Open HR Relay, enter the MAC address and tap Start (grant the permissions).
4. Tap "Disable battery optimization" and allow it.

## Broadcast intents
| Action | Extras |
|---|---|
| `local.hrrelay.UPDATE` | `bpm` (String), `ts` (String, epoch ms) |
| `local.hrrelay.STATUS` | `state` (String), `ts` (String, epoch ms) |

`state` is one of: `connected`, `disconnected`, `lost`, `recovered`, `no_hr_service`, `stopped`.
`lost` is sent once when no heart rate has arrived for the configured number of seconds;
`recovered` is sent when data resumes.
All extras are sent as strings, because MacroDroid can only store string extras.

## MacroDroid
Add an "Intent Received" trigger with the action above, and store the extra `bpm` in a string variable.
To compare it numerically, copy it into an integer variable (Set Variable) first.

## Status

Tested:
- Galaxy Watch Ultra with the "Heart for Bluetooth" watch app, on one Android phone
- HUAWEI WATCH GT 4 with its built-in heart rate sharing, on one Android phone
- Amazfit GTR 4 with its built-in "heart rate push" app, on one Android phone
- Garmin vivoactive 5 with its heart rate broadcast mode, on one Android phone
- Receiving heart rate and storing it in a MacroDroid variable via the "Intent Received" trigger
- `lost` / `recovered` are reported when heart rate stops and resumes (Galaxy Watch Ultra)

Not tested yet:
- Behaviour with the watch screen off on the HUAWEI WATCH GT 4, the Amazfit GTR 4 and the Garmin vivoactive 5
- Long-term stability on these watches
- Other watches and phones

This is an early version. Feedback and test reports are welcome.

## Known issues

- On my Galaxy Watch Ultra with "Heart for Bluetooth", heart rate updates stop (or become sparse) while the watch screen is off, even though the BLE connection stays up. Turning the watch screen on restores them. This seems to be a watch-side power-saving behaviour, not something this app can fix. `lost` / `recovered` reflect this.

## Notes
- Only the standard Heart Rate Measurement format is parsed (8-bit and 16-bit values).
- Reconnects automatically with backoff.
