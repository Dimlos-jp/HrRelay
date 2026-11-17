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

## Usage
1. Make sure the watch app is broadcasting. Disconnect other BLE clients (e.g. nRF Connect) first.
2. Open HR Relay, enter the watch's MAC address and tap Start (grant the permissions).
3. Tap "Disable battery optimization" and allow it.

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
- Receiving heart rate and storing it in a MacroDroid variable via the "Intent Received" trigger

Not tested yet:
- Long runs with the phone screen off
- Whether `lost` / `recovered` are reported correctly when the watch app is stopped and restarted
- Other watches and phones

This is an early version. Feedback and test reports are welcome.

## Notes
- Only the standard Heart Rate Measurement format is parsed (8-bit and 16-bit values).
- Reconnects automatically with backoff.