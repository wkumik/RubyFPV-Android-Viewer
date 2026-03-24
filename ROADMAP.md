# RubyFPV Android Viewer — Roadmap

## V1 — Basic Video Viewer (current)

Core functionality: plug in phone via USB-C, see what the pilot sees.

- [x] UDP listener on port 5001 for raw H.264 stream from Ruby ground station
- [x] NAL unit parsing from raw byte stream (start code scanning)
- [x] Hardware H.264 decoding via MediaCodec with low-latency flag
- [x] Fullscreen landscape display on SurfaceView
- [x] Auto-recovery on stream loss (3-second watchdog)
- [x] Stream stats overlay (bitrate, packets, NAL count) — tap to toggle
- [x] USB tethering status detection and user guidance
- [ ] Test with real Ruby ground station hardware
- [ ] Basic settings screen (port configuration)
- [ ] App icon and branding

## V2 — Ruby OSD Overlay

Display Ruby's full OSD (graphs, radio stats, interference indicators) on the Android screen.

**Requires Ruby-side modification** — propose to Petru (main Ruby dev):

- [ ] Ruby-side: read rendered OSD framebuffer after each render cycle
- [ ] Ruby-side: compress OSD frame (RLE or PNG — mostly transparent pixels, compresses well)
- [ ] Ruby-side: send compressed OSD frames via UDP port 5002 to USB tethered device at ~10-15 FPS
- [ ] Ruby-side: toggle in USB forwarding settings to enable/disable OSD forwarding
- [ ] Android: receive OSD frames on UDP port 5002
- [ ] Android: decompress and render as transparent overlay on top of video
- [ ] Android: OSD opacity slider
- [ ] Android: option to hide/show OSD independently of video

## V3 — Enhanced Viewer

- [ ] H.265 support (requires Ruby-side: enable H.265 USB forwarding, currently blocked)
- [ ] Screen recording (save session as MP4 to phone storage)
- [ ] Screenshot button
- [ ] Pinch-to-zoom (digital zoom on video feed)
- [ ] Stream health HUD (packet loss %, latency estimate, signal indicator)
- [ ] Landscape orientation lock toggle

## V4 — Telemetry & DVR

- [ ] Receive Ruby telemetry forwarding (FC data: battery, GPS, altitude, speed)
- [ ] Render basic flight telemetry as a secondary overlay (for when OSD forwarding is not available)
- [ ] Telemetry logging to CSV/GPX files
- [ ] DVR with telemetry burn-in option
- [ ] Flight session history and playback

## V5 — Advanced Features

- [ ] Dual display mode (video on external screen via USB-C to HDMI, controls on phone)
- [ ] Split-screen spectator mode (multiple phones via USB hub for race judges/spectators)
- [ ] Head tracking passthrough (phone IMU → Ruby → gimbal, for VR holder use)
- [ ] Custom OSD element positioning on Android side
- [ ] Theme/color customization

## Ruby-Side Improvements (to propose upstream)

- [ ] Bump default USB packet size (1024 → 8192+, USB tethering has no MTU concern)
- [ ] Periodic SPS/PPS re-send (allows mid-stream connection without waiting for keyframe)
- [ ] Enable H.265 USB forwarding path
- [ ] Simple heartbeat protocol (app pings Ruby so ground station knows a client is connected)
- [ ] OSD framebuffer forwarding (V2 dependency)
