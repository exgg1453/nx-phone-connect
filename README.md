# NX Phone Connect

Remote control between your own Android and iPhone devices. Licensed under AGPL-3.0.

## Wire protocol

TCP over Bonjour service `_nxconnect._tcp`. Each message is one kind byte, a 4-byte big-endian payload length, then the payload.

| Kind | Name | Direction | Payload |
| --- | --- | --- | --- |
| 1 | hello | controller to host | pairing code, UTF-8 |
| 2 | accepted | host to controller | empty |
| 3 | rejected | host to controller | empty |
| 4 | frame | host to controller | JPEG image |
| 5 | touch | controller to host | JSON `{phase: began\|moved\|ended, normalizedHorizontalPosition, normalizedVerticalPosition}` |
| 6 | key | controller to host | JSON `{name: back\|home\|recents}` |

## Android

The `android` directory holds the Android app. It can share its screen (MediaProjection) and be controlled through an accessibility service, and it can view or control other devices.

## iOS limits

iOS does not let an app inject touches into the system, so an iPhone can control an Android device, while an Android device can only view an iPhone's shared screen. Full-screen sharing uses a broadcast extension that shares its pairing code with the app through the App Group `group.com.nxteam.nxconnect`; the signing tool must keep that App Group for the code to appear in the app.
