# FlashCam-Air3

**Unlock the full 16MP camera on your INMO Air3 AR glasses — no root required.**

FlashCam-Air3 is an open-source camera app that bypasses the INMO Air3's firmware-imposed 3MP capture limit by using the Android Camera2 `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION` API. The Air3's 16MP sensor (4656×3496) is fully accessible to standard apps through the max-resolution stream map — INMO just never exposed it in their default camera app.

## Key Discovery

The INMO Air3 (IMA301, Android 14) advertises `ULTRA_HIGH_RESOLUTION_SENSOR` capability. While the default stream map limits JPEG output to 2048×1536 (3.1MP), the **maximum-resolution stream map** exposes:

| Format | Resolution | Megapixels |
|--------|-----------|------------|
| JPEG | 4608×3456 | 15.9 MP |
| RAW/DNG | 4656×3496 | 16.3 MP |

This app uses the standard Camera2 API to capture at these full resolutions. No root, no vendor file edits, no firmware mods.

## Features

### Still Photo Modes
- **8MP / 12MP / 16MP** toggle — selects the closest available camera output size
- **16MP** uses `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION` for true full-sensor capture
- JPEG quality fixed at 100%
- Proper pixel-rotated orientation (images saved upright, not relying on EXIF rotation)

### RAW/DNG Support
- Toggle DNG capture on/off (default: off to reduce capture delay)
- Proper `.dng` files via `DngCreator` with full metadata
- Opens in Lightroom, Photoshop, Google Photos, and any DNG-compatible editor
- DNG and JPEG share the same timestamp for easy pairing

### Video Recording
- **1080p @ 30fps** and **4K @ 30fps** (if supported by HAL)
- Landscape and Portrait orientation modes
- Recording indicator with timer
- Proper orientation metadata for correct gallery playback

### Camera Controls
- **Tap-to-focus** with visual focus ring indicator
- **Exposure compensation** (EV+/EV-) with real-time preview adjustment
- **AF/AE status indicator** showing focus and exposure state

### Orientation
- Landscape (default) and Portrait modes
- **Pixel rotation** — saved images are physically rotated to be upright
- EXIF orientation set to NORMAL after rotation for maximum compatibility

### Gallery Integration
- Photos saved to `Pictures/FlashCam-Air3/` for automatic Gallery visibility
- MediaStore scanning ensures immediate appearance in Gallery
- File naming: `FlashCam_YYYYMMDD_HHMMSS_<8MP|12MP|16MP>.jpg`

### Debug/Receipt System
- Toggle debug receipts on/off (default: off)
- After each capture, shows: mode, orientation, requested vs actual dimensions, file path, file size
- Copy receipt to clipboard or export full capture log

## Device Compatibility

| Field | Value |
|-------|-------|
| Device | INMO Air3 (IMA301) |
| OS | Android 14 (SDK 34) |
| Build tested | Air3_DU_V3.11.027_202601230319 |
| SoC | Qualcomm Snapdragon 6 Gen 1 |
| Camera sensor | 16MP (4656×3496 physical) |

> **Note:** This app requires `ULTRA_HIGH_RESOLUTION_SENSOR` capability and `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` in the Camera2 HAL. It was developed for and tested on the INMO Air3. It may work on other devices with similar Camera2 capabilities.

## Install

### From GitHub Release (recommended)
1. Download the APK from the [Releases](../../releases) page
2. Transfer to your Air3 via Google Drive, USB, or direct download
3. Open the APK on the Air3
4. If prompted, enable "Install from unknown sources" for the app you used to open the APK
5. Tap Install

### From Source
```bash
# Clone
git clone https://github.com/Flash-Bri/FlashCam-Air3.git
cd FlashCam-Air3

# Build (requires JDK 17 + Android SDK 34)
./gradlew assembleDebug

# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Build Requirements

- JDK 17
- Android SDK 34
- Android Build Tools 34.0.0
- Gradle 8.4
- Android Gradle Plugin 8.2.0

## Permissions

| Permission | Purpose |
|-----------|---------|
| `CAMERA` | Camera access for preview and capture |
| `RECORD_AUDIO` | Audio recording for video mode |
| `READ_MEDIA_IMAGES` | Gallery access (Android 13+) |
| `READ_MEDIA_VIDEO` | Gallery access for video (Android 13+) |

## Known Limitations

1. **Max-res capture requires session switch** — switching between default and max-res modes requires closing and reopening the camera session, which causes a brief preview interruption (~0.5s)
2. **No autofocus during max-res capture** — some devices may not support AF in max-res mode; the app falls back gracefully
3. **DNG files are large** — ~31MB per capture at full sensor resolution
4. **Video is always default-mode** — video recording uses the default stream map (not max-res)
5. **Portrait mode uses pixel rotation** — this adds processing time after capture

## How It Works

The INMO Air3's camera HAL (Hardware Abstraction Layer) exposes two stream configuration maps:

1. **Default map** (`SCALER_STREAM_CONFIGURATION_MAP`): Max JPEG 2048×1536 (3.1MP)
2. **Max-res map** (`SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`): Max JPEG 4608×3456 (15.9MP)

Standard camera apps only query the default map. FlashCam-Air3 queries the max-res map and uses `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION` in both the session configuration and capture requests to access the full sensor resolution.

This is a standard Android Camera2 API feature (added in API 31) designed for Quad-Bayer/QCFA sensors. INMO implemented it correctly in their HAL — they just never exposed it in their own camera app.

## Troubleshooting

| Issue | Solution |
|-------|---------|
| "Camera open failed" | Close all other camera apps, reboot the Air3, try again |
| Max-res capture times out | Ensure no other app is using the camera; reboot |
| Images appear sideways | Update to latest version; pixel rotation should handle this |
| DNG won't open in editor | Ensure you're using a DNG-compatible editor (Lightroom, RawTherapee, etc.) |
| App crashes on launch | Ensure Camera permission is granted in Settings > Apps > FlashCam-Air3 |

## License

MIT License — see [LICENSE](LICENSE) for details.

## Credits

- Developed with assistance from [Manus AI](https://manus.im)
- Inspired by the INMO Air3 community's discovery that the 16MP sensor was firmware-locked to 3MP
- Camera2 max-res API documentation: [Android Developer Docs](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
