# RubyFPV Android Viewer

Android companion app for [RubyFPV](https://rubyfpv.com). Two modes:

1. **Recordings** (primary) — DJI-style transfer of onboard HEVC recordings off the
   drone over its Wi-Fi, with on-device preview and sharing.
2. **Live view** (secondary) — the live H.264 feed from a Ruby ground station over
   USB-C tethering.

## Recordings — download & preview

When the drone is in **phone-transfer mode** (its radio comes up as a Wi-Fi AP), the
app connects over SSH and lets you browse the onboard SD card:

1. Join the drone's Wi-Fi and tap **Connect**
2. Browse recorded clips with thumbnails, duration, size and date
3. **Download** a clip — it transfers over Wi-Fi and is losslessly rewrapped from
   `.ts` to `.mp4` on-device (zero re-encode) for reliable seeking and sharing
4. **Play** it natively (hardware HEVC decode) or **Share** to anything

Transfer uses an SSH `exec` + `cat` stream rather than SFTP, because the drone's
dropbear build ships no SFTP/SCP subsystem.

## Live view — how it works

1. Phone connects to Ruby ground station via USB cable
2. USB tethering is enabled on the phone (creates a network link)
3. Ruby detects the phone and sends raw H.264 video over UDP port 5001
4. The app decodes and displays the video in fullscreen with minimal latency

(Open it from the **⋮ → Live view (USB)** menu on the Recordings screen.)

## Features

- DJI-style recordings album: thumbnails, lossless `.ts`→`.mp4` remux, share sheet
- Hardware H.264 / HEVC decoding via Android MediaCodec / MediaPlayer
- Fullscreen landscape live display, auto-recovery on stream loss (3-second watchdog)
- Live stream stats overlay (tap screen to toggle): bitrate, packet rate, NAL rate
- Minimal latency — designed for FPV use

## Requirements

- Android 7.0+ (API 24)
- USB data cable (not charge-only)
- RubyFPV ground station with USB video forwarding enabled

## Ruby ground station setup

1. Go to **Controller > Video Forward** in the Ruby menu
2. Enable **Video Forward To USB Device**
3. Set type to **Raw (H264)**
4. Port: **5001** (default)

## Install

Download the latest APK from [GitHub Actions](https://github.com/wkumik/RubyFPV-Android-Viewer/actions) (build artifacts) or build from source.

### Build from source

```
git clone https://github.com/wkumik/RubyFPV-Android-Viewer.git
cd RubyFPV-Android-Viewer
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Testing without a ground station

You can test the app using ffmpeg from a PC while the phone is USB-tethered to the PC:

```
ffmpeg -f lavfi -i testsrc=size=1280x720:rate=30 \
  -pix_fmt yuv420p -c:v libx264 -profile:v baseline \
  -tune zerolatency -g 30 -bsf:v dump_extra \
  -f h264 udp://<phone_ip>:5001?pkt_size=1024
```

## Roadmap

See [ROADMAP.md](ROADMAP.md) for planned features including:
- Ruby OSD overlay (V2)
- H.265 support (V3)
- Screen recording / DVR (V4)

## Credits

- [RubyFPV](https://rubyfpv.com) by Petru Soroaga
- Video decoding architecture inspired by [OpenIPC Decoder](https://github.com/OpenIPC/decoder) (MIT License)
- NAL parsing based on [Consti10/myMediaCodecPlayer-for-FPV](https://github.com/Consti10/myMediaCodecPlayer-for-FPV)

## License

MIT — see [LICENSE](LICENSE)
