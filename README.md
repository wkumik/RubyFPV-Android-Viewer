# RubyFPV Android Viewer

Android companion app for [RubyFPV](https://rubyfpv.com). Browse, download, play and
manage the onboard `.ts` recordings off your drone's SD card over Wi-Fi — DJI-Fly style —
then tap **Return to FPV mode** to reboot the drone back to flight. A live USB viewer
(the original `MainActivity`) is still available behind the ⋮ menu.

## Phone-transfer workflow

1. **On the Ruby Ground Station**, press **Enter phone-transfer mode**. The drone reboots
   its radio into an open Wi-Fi access point named `RubyFPV-<MAC4>` at `192.168.4.1`.
2. **On your phone**, join that Wi-Fi network.
3. Open the app and tap **Connect**. It SSHes the drone and lists the onboard recordings.
4. Browse clips with thumbnails, **download**, **play**, and **delete** as you like.
5. When you're done, tap **⋮ → Return to FPV mode**. The drone reboots out of
   phone-transfer AP mode back into normal flight/FPV (ready to fly again in ~45 s).

The drone runs **dropbear**, whose build ships **no SFTP / SCP subsystem**, so the app
never opens an SFTP channel. It drives plain `exec` channels instead: listing with
`stat`, transferring by streaming `cat`. Recordings live in `/mnt/mmcblk0p1/ruby` and the
default login is `root@192.168.4.1:22` (editable in **⋮ → Connection settings**).

## Recordings — download & playback

- **Thumbnails** are decoded on-device from the first few MB of each clip (it starts on a
  keyframe). The onboard recordings are **HEVC in MPEG-TS**, which the Android framework
  demuxer reports as 0 tracks; the app uses media3 1.6.0's
  `ExperimentalFrameExtractor` — the same ExoPlayer pipeline used for playback — to pull
  the first frame.
- **Playback** plays the downloaded `.ts` directly in ExoPlayer (no remux / re-encode).
- **Multi-select**: long-press a clip to enter selection mode, then bulk-delete.
- **Per-item delete** lets you choose where to remove a clip from: **phone**, **drone SD
  card**, or **both**.
- **Share** a downloaded clip to any app via the system share sheet.

## Live USB viewer

The original USB viewer is still present, reachable from **⋮ → Live view (USB)**:

1. Connect the phone to the Ruby ground station via USB cable and enable USB tethering.
2. Ruby forwards raw H.264 video over UDP port 5001.
3. The app decodes and displays it fullscreen with minimal latency.

To enable forwarding on the ground station: **Controller > Video Forward**, turn on
**Video Forward To USB Device**, type **Raw (H264)**, port **5001**.

## Features

- Recordings browser with thumbnails, file size, date and on-drone / on-device state
- ExoPlayer playback of `.ts` HEVC clips (hardware decode, no remux)
- Multi-select (long-press) with bulk delete
- Per-item delete: phone / drone SD card / both
- Share downloaded clips via the system share sheet
- **Return to FPV mode** — reboots the drone out of phone-transfer AP back into flight
- Live USB viewer (raw H.264 over UDP 5001) behind the ⋮ menu
- In-app diagnostics / debug log viewer

## Requirements

- Android 7.0+ (min SDK 24, target SDK 35)
- A RubyFPV drone that supports phone-transfer mode (open AP `RubyFPV-<MAC4>` @ 192.168.4.1)
- For the live USB viewer: a USB data cable and a Ruby ground station with USB video
  forwarding enabled

## Build from source

Requires **Java 21** and the Android SDK (`ANDROID_HOME` set, or a `local.properties`
with `sdk.dir`).

```sh
git clone https://github.com/wkumik/RubyFPV-Android-Viewer.git
cd RubyFPV-Android-Viewer
./gradlew :app:assembleDebug
```

The APK is stamped with its version and lands at:

```
app/build/outputs/apk/debug/RubyFPV-v<versionName>-b<versionCode>-debug.apk
```

(e.g. `RubyFPV-v1.6-b8-debug.apk`).

Install it with adb:

```sh
adb install -r app/build/outputs/apk/debug/RubyFPV-v1.6-b8-debug.apk
```

## Tech notes

- media3 1.6.0 (ExoPlayer + UI + Transformer's `ExperimentalFrameExtractor`)
- jsch (`com.github.mwiede:jsch`) for SSH over the drone's dropbear (exec channels only —
  no SFTP)
- Min SDK 24, target / compile SDK 35, Java 17 source/target

## Credits

- [RubyFPV](https://rubyfpv.com) by Petru Soroaga
- Live USB viewer NAL parsing based on
  [Consti10/myMediaCodecPlayer-for-FPV](https://github.com/Consti10/myMediaCodecPlayer-for-FPV)

## License

MIT — see [LICENSE](LICENSE)
