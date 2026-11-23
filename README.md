# Reson

Reson is a native Android music player for device audio and read-only SMB music shares on your local network. Both sources use the same background player.

## Build and install

Use JDK 17 or 21, Android SDK Platform 37.0, Build Tools 36.0.0 and Platform Tools. Set `ANDROID_HOME` to the SDK path or open the project in Android Studio and let it configure the SDK. The app supports Android 10 (API 29) and newer.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `gradlew.bat` on Windows. For `adb install`, enable USB debugging on your phone, connect it by USB, and accept the debugging prompt.

### Upgrade an existing installation

Install the APK over the existing copy of Reson. Use the same application ID and signing certificate, and a version code at least as high as the installed APK. `adb install -r` keeps the app’s data, including playlists and downloaded tracks. Uninstalling or clearing app storage removes that data. [Android update requirements](https://developer.android.com/google/play/app-updates)

Gradle normally uses a different debug key on each development machine. To update a debug APK from another machine, use the original debug keystore with `./gradlew :app:assembleDebug -PresonDebugKeystore=/path/to/debug.keystore`. Keep the keystore outside the repository. If Android reports a signature mismatch, rebuild with the original key before trying again. [Android app signing](https://developer.android.com/studio/publish/app-signing)

### Build for Google Play

Run `./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease` to check the app and build the release bundle. Use Android Studio’s **Generate Signed Bundle / APK** to sign a release Android App Bundle with the dedicated Play upload key. Upload that signed `.aab` to the Internal testing track. Keep the upload key and its password outside the repository, and increment `versionCode` for each new Play upload. [Play App Signing](https://developer.android.com/studio/publish/app-signing)

## Connect to a Synology NAS

1. On the NAS, enable **SMB service** in DSM under **Control Panel → File Services → SMB**. Use SMB2 or SMB3 and give a NAS account read access to the music share. [Synology SMB setup](https://kb.synology.com/en-us/DSM/tutorial/access_nas_from_mac)
2. Connect the phone to the same LAN. In Reson, open **NAS → Add NAS**. If you see an **Allow access** button, tap it and grant local network access.
3. Enter the NAS’s local IP address or hostname, the NAS account’s username and password, and the share name, such as `music`. Port **445** is the default. Leave **Domain** blank for a typical local DSM account.
4. Tap **Browse shares** to pick a visible share, or enter its exact name. Tap **Test connection**, then **Save connection**. Tap the saved connection, open a folder, and select a track to stream it.
5. Use **Use as starting folder** to open that folder by default. **Find on network** can find servers that advertise SMB over mDNS. If discovery or hostname lookup comes up empty, use the NAS’s local IP.

If Android reports a blocked network connection immediately after updating from version 0.1.0, restart the phone and try again.

## Build your library

1. Open **Library** and grant music access to index audio from this device.
2. For NAS music, browse to a folder in **NAS** and choose **Add folder to library**. Reson scans its subfolders. You can add several folders on a connection and remove them from **Manage library folders**.
3. Use **Search library** to search by title, artist, or album. Open **Albums** or **Artists** to browse groups. Selecting a track queues the tracks in the current results or group.
4. Tap **Refresh library** to rescan device audio and selected NAS folders. Cached NAS tracks remain visible if a share is temporarily unavailable. Playback of those tracks requires the NAS connection.

## Listen offline

1. In a NAS folder, tap **Download** beside a track. The same action is available in **Now playing** for a NAS track.
2. To keep a whole folder offline, tap **Keep folder offline** and confirm. Reson downloads audio in the folder and its subfolders, then checks for additions and changes about every 12 hours when Android can run the job on Wi-Fi or Ethernet. Use **Downloads → Sync now** to request an earlier check. If the NAS is unreachable, Reson retries up to twice, then waits for the next scheduled check or a manual retry. Reson keeps 128 MiB of free space in reserve and pauses a download if there is insufficient room. Cancel a transfer or stop syncing its folder to remove any unfinished copy.
3. Open **Downloads** to play saved tracks, retry or cancel transfers, stop syncing a folder, or remove a copy. Downloaded tracks also appear in the Library. Playing a NAS track uses its saved copy when available.
4. Stopping sync or deleting the source file from the NAS leaves the saved copy available. To free space, remove the copy from **Downloads**. A track still in an actively synced folder can be downloaded again.
5. In a playlist’s menu, choose **Keep playlist offline** to download its NAS tracks. Rows show waiting, downloading, and saved tracks. For a failed track, open its menu and choose **Retry download**. Stopping sync or removing a track from the playlist keeps its saved copy available in **Downloads**.
6. The download bar shows progress while you browse or play music. Tap it to open **Downloads**. **Review cleanup** shows how much space you can reclaim from copies outside active syncs. Manually downloaded copies and copies saved before this feature are protected from cleanup.

## Use the player and collections

- Open a track’s menu to choose **Play next**, add it to the end of the queue, mark it as a favourite, or add it to a playlist. You can create a playlist from that menu.
- Open **Playlists** to manage playlists and favourites or revisit playback history. Playlist handles let you drag tracks into order. Track menus also let you reorder or remove tracks and rename or delete the playlist. Use **Shuffle play** to start a playlist or your favourites in shuffled order.
- In **Now playing**, toggle shuffle or tap repeat to cycle through off, all tracks, and one track. Open **Queue** to jump to a track, remove it, or drag it into a different position. The track menu also offers Move up and Move down. Turn off shuffle to change the manual order. **Settings → Resume last queue** restores the queue and position when you reopen Reson. The restored queue opens paused.
- The **Playlists** menu imports M3U and M3U8 documents. To import a playlist stored on your NAS, tap it in the NAS browser. Relative paths in that playlist resolve from its NAS folder. An individual playlist’s menu exports it as M3U or M3U8 with references to the original audio files.
- **Smart playlists** include favourites absent from recent playback history and albums added in the last 30 days. Reson starts tracking library addition dates with this version. Use a smart playlist’s menu to keep its NAS tracks offline.
- **Settings → ReplayGain** offers **Off**, **Track**, and **Album**. Track mode balances tagged tracks individually. Album mode uses the album’s gain when it is available. ReplayGain applies to playback on the phone, including audio routed to Bluetooth.
- Tap **Library** in the header to return directly to the Library, including from a nested NAS folder.
- **History** keeps the 200 most recent playback starts. Pausing and resuming a track keeps the same entry. Use **Clear history** to remove the list.

## Change the skin color

Open **Library → Settings → Skin color** and choose **Green**, **Purple**, or **Orange**. Green is the default. Reson remembers your choice and follows your phone’s light or dark mode.

## Use the visualizer

1. In **Library**, open **Settings** and turn on **Visualizer**. It defaults to off.
2. Play a track and open **Now playing → Visualizer** to see the phone waveform.
3. For the TV, tap **Start TV mode** and open the displayed address in a compatible TV browser on the same Wi-Fi. Select **Play on this TV**. The TV plays the audio and draws the spectrum and waveform while the phone is locked.
4. Use **Stop TV mode** or switch **Visualizer** off in Settings to end the session. Dismissing Reson from Recents or stopping it from the playback notification also ends TV mode.

TV mode uses a computer or TV browser and a single-use local Wi-Fi link with a four-character code. The link expires after five minutes if unused. It needs a browser with Web Audio and streaming Fetch support. Use it on a trusted Wi-Fi network and share the address only with the intended device. The browser draws the animation. The phone analyzes audio for its own display only while the Visualizer screen is open.

## Cast music to a TV

Play a track, tap the Cast icon in **Now playing**, and choose a Chromecast or TV with Google Cast on the same local network. The receiver plays the music in its built-in player. The animated visualizer is available through the browser link. The phone supplies the audio while casting, and the receiver must support the track’s audio format.

## Use Reson with Android Auto

While parked, open Reson on your phone and grant music access. For NAS music, choose a library folder or download the tracks you want on the road. Connect the phone to Android Auto and open Reson from the media app selector.

For a sideloaded debug APK, enable **Unknown sources** in Android Auto’s developer settings on the test phone. [Android Auto testing instructions](https://developer.android.com/training/cars/testing#allow-unknown-sources)

- **Library** includes songs, albums, and artists. **Downloads**, **Playlists**, and **Favourites** are available from the same browse screen.
- Select a track to start playback and queue nearby tracks from that section. Voice requests can find songs, albums, artists, or playlists.
- Use **Downloads** for music away from the NAS. The car and phone use the same playback session and queue.

### Head-unit and Bluetooth checks

1. With the vehicle parked, open Reson on an Android Auto head unit or the Desktop Head Unit and browse each section. Start with the phone player paused and check that opening Reson keeps it paused until you select music. [Desktop Head Unit setup](https://developer.android.com/training/cars/testing/dhu)
2. Play a downloaded track and test play, pause, next, and previous from the head unit or steering wheel. Try a voice request by song or playlist name.
3. Pair a Bluetooth headset or car stereo, select it for media output, and check that audio plays through it. Test play, pause, next, and previous on the accessory. Disconnect it during playback and confirm Reson pauses.

## Test on a phone

1. Copy two or more audio files to the phone's Music directory and wait for Android to index them. Enable airplane mode. For format coverage, try MP3, FLAC, AAC, M4A, OGG, and WAV files.
2. Open Reson, deny music access, and verify the library offers a path to grant it. Grant access and check that your tracks appear. Copy another track and refresh the library after Android indexes it.
3. Tap a track. Open the full player, seek, pause, resume, and use next and previous.
4. Press Home, then lock the screen and confirm playback continues. Wake the screen and try the lock-screen media controls. Reopen Reson and verify the current track and playback position are displayed. Then remove Reson from Recents or dismiss its playback notification and check that music stops.
5. Try media keys from Bluetooth headphones. Disconnect the headphones while audio is playing and verify playback pauses.
6. Switch the device between light and dark mode and reopen the app.

### SMB integration test

Use a read-only Synology share with two audio files in a nested folder. In Reson, test a bad password and then the correct one. Browse shares and open the nested folder, then stream a track, seek near its end, and advance to the next track. Turn Wi-Fi off during playback and allow any buffered audio to finish. Restore Wi-Fi and tap **Play** to retry, or use **Retry** in the folder browser. Save the nested folder as the starting folder, restart Reson, and check that the saved connection opens there. Confirm the source files are intact.

To check the library, choose **Add folder to library** in two folders on the same connection. Check the track, album, and artist views, search by a tag, and play one of the results. Reopen Reson and confirm both folders’ entries and artwork are still present. Add, change, and remove tracks on the test share, then refresh the library. Turn off Wi-Fi and refresh again to verify the previous NAS entries are retained.

### Offline integration test

Download a tagged NAS track, enable sync on a folder containing nested audio, and wait for the tracks to appear under **Saved tracks**. Disconnect Wi-Fi and verify playback and seeking from **Downloads** and from the Library. Reconnect, then add, replace, and delete source files on the test share. Use **Sync now** and check that new and updated tracks arrive and the deleted source still has a playable copy. Interrupt a larger transfer, restore Wi-Fi, and verify it completes before it appears under **Saved tracks**.

While a large file is downloading, open the Library and start another track. Check that the download bar updates while you browse, and tap it to open **Downloads**. Keep a playlist containing an inaccessible NAS file offline, fix the share permission, then use **Retry download** on the failed track. Stop syncing the playlist and open **Review cleanup**. Confirm that an unrelated manual download and any copy shared with an active folder are excluded from the preview.

### Playback transition checks

1. On the previous APK, save a NAS folder, create a playlist, and download a track. Install the new APK with `adb install -r` over that installation. Check that the folder and playlist still appear and the download plays with Wi-Fi off. Check that previously protected downloads remain protected in **Review cleanup**.
2. While shuffle is on, choose **Play next** from a track’s menu and check that it follows the current track. Try this on the phone and a Cast receiver. With a shuffled queue, cast to the receiver and back and compare the upcoming tracks.
3. Reopen the app with Wi-Fi off after playing a NAS track. Check that the queue and position restore paused. Restore Wi-Fi and press **Play** to retry.
4. Pause a playlist, leave Reson in Recents, and return after a long pause. Check that its list and queue are visible. Lock the phone and try play, pause, and skip from the Bluetooth watch.
5. Play two tracks with different ReplayGain tags. Compare Off, Track, and Album settings and include a track without tags to check that gain resets across the transition.

See [architecture](docs/ARCHITECTURE.md) and [roadmap](docs/ROADMAP.md).
