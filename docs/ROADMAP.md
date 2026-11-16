# Reson roadmap

| Milestone | Scope | Status |
| --- | --- | --- |
| 1. Android foundation | Native Android project, themes, navigation, local track discovery, playback service, debug APK | Implemented |
| 2. NAS connectivity | Secure Synology SMB configuration, share and folder browsing, streaming, connection recovery | Implemented |
| 3. Music library | Room index, metadata and artwork, source identity, incremental scans, search and grouped views | Implemented |
| 4. Offline music | Downloads, folder synchronization, local-copy preference | Implemented |
| 5. Full music player | Playlists, favourites, queue editing, shuffle, repeat, playback history | Implemented |
| 6. Car integration | Android Auto browsing, Bluetooth and media button verification | Implemented, hardware checks pending |
| 7. Release preparation | Privacy, permission, performance and battery review, Google Play configuration, signed build | Planned |

## Milestone 1 coverage

The debug APK targets Android 17 and runs on Android 10 and newer. The library shows local audio exposed by MediaStore. The player supports seeking, next and previous track, audio focus, and Android media controls through Media3. The playback order is created from the current library when a track is selected and lives in the playback service. Four JVM tests cover metadata normalization, format eligibility, and playback order.

The debug build, APK signature verification, JVM tests, and Android lint passed. An Android 10 emulator verified permission denial and recovery, MediaStore updates, offline WAV playback, seeking, next track, background pause and resume, and playback with the screen asleep. Physical-device checks cover the remaining audio formats, lock-screen controls, and Bluetooth.

## Milestone 2 coverage

Version 0.2.0 adds saved NAS connections, optional mDNS discovery, share selection, directory browsing, and direct SMB streaming. The player uses the current NAS folder as the playback order. Unified indexing, downloads, and folder synchronization are covered by later milestones. [Synology integration test](../README.md#smb-integration-test)

The signed debug build passed Android lint and 11 JVM tests. An Android 10 emulator and a read-only SMB2 test share verified incorrect-password handling, disk share enumeration, nested browsing, encrypted profile storage, direct WAV streaming, seeking, track switching, and retry after Wi-Fi loss. The final APK reopened the saved starting folder, streamed with the screen asleep, and played an existing device track. The test files were unchanged. Validation against a physical Synology, SMB3, and mDNS advertisements remains part of the device checklist.


## Milestone 3 coverage

Version 0.3.0 adds a persistent Room library for device audio and one recursively indexed folder per NAS connection. The library displays metadata and cover art, searches track and album tags, and groups tracks by album and artist. Album playback follows disc and track tags where available. Device tracks use MediaStore identities and NAS tracks use connection, share, and path identities.

Scans reuse metadata and artwork for unchanged files. A successful scan adds or updates tracks and removes entries for files it no longer finds. A failed NAS scan preserves the last complete index. Use **Use as library folder** in the NAS browser to choose the scope, then **Refresh library** to scan it again. [Library setup](../README.md#build-your-library)


## Milestone 4 coverage

Version 0.4.0 adds individual NAS downloads, recursive folder sync, and a Downloads screen. WorkManager runs transfers on Wi-Fi or Ethernet and supports manual sync and periodic checks. Interrupted transfers resume for the same source revision. The player prefers complete local copies, and downloaded tracks remain available after a source file is removed or a folder stops syncing. The Room migration preserves the existing library. [Offline setup](../README.md#listen-offline)

The signed debug build passed 17 JVM tests and Android lint. An Android 15 emulator upgraded from 0.3.0 with its existing library, downloaded from a read-only SMB2 share, and synchronized additions and replacements while retaining a deleted source's copy. With Wi-Fi disabled and the test share stopped, Media3 played and sought through downloaded audio using both NAS and local-only playback IDs.


## Milestone 5 coverage

Version 0.5.0 adds editable playlists, favourites, and playback history under **Collections**. Track menus can append to the playback queue, and the Queue screen supports track selection, removal, and manual reordering. Media3 handles shuffle and repeat, with playback mode settings saved across app restarts. The Room migration preserves the library and offline downloads. [Player and collections](../README.md#use-the-player-and-collections)

The signed debug build passed 20 JVM tests and Android lint. An Android 15 emulator verified the upgrade from 0.4.0, duplicate queue entries and edits, shuffle play, repeat-one playback, and history without duplicate entries on pause and resume. Playlists, favourites, history, shuffle, and repeat persisted after reopening the app. The Collections screen opened and created a playlist through the Android UI.


## Milestone 6 coverage

Version 0.6.0 adds Android Auto browsing and voice search through the existing background player. The car catalog exposes the library, downloads, playlists, and favourites. Car and phone controls use the same Media3 playback session. [Android Auto setup and hardware checks](../README.md#use-reson-with-android-auto)

The signed debug build passed 24 JVM tests and Android lint. An Android 15 emulator verified browsing and playback through a separate trusted platform media browser, rejection of untrusted browsers and raw playback URIs, offline playback, and voice search. The emulator also verified system media buttons, pause on simulated audio-route loss, and device-track filtering when music permission was revoked. Android Auto head-unit display and playback through a physical Bluetooth accessory still need hardware checks.
