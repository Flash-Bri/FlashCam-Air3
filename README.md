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

### Full-Frame Capture (One-Mode Camera)
- **8MP / 12MP / 16MP** toggle — selects the closest available camera output size
- **16MP** uses `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION` for true full-sensor capture
- JPEG quality fixed at 100%
- **Always full frame** — no crop, no portrait/landscape mode. The saved image is exactly what the sensor captures. Users crop later if desired.
- Proper pixel-rotated orientation (images saved upright, not relying on EXIF rotation)

### Upright Preview & Capture
- Preview shows the **real world upright** (matching reality) in the Air3's landscape-locked UI
- Saved JPEG matches the preview framing and orientation exactly
- Uses the **inverse rotation formula** `(displayDegrees - sensorOrientation + 360) % 360` to correctly map sensor buffer to display
- **Software pixel rotation** — saved images are physically rotated to be upright using `android.graphics.Matrix`. `JPEG_ORIENTATION` is set to 0 (never trusted). EXIF orientation is always NORMAL for maximum compatibility.
- One shared rotation helper used for preview transform, JPEG rotation, and video orientation hint — no divergence possible.

### RAW/DNG Support
- Toggle DNG capture on/off (default: off to reduce capture delay)
- Proper `.dng` files via `DngCreator` with full metadata
- Opens in Lightroom, Photoshop, Google Photos, and any DNG-compatible editor
- DNG and JPEG share the same timestamp for easy pairing

### Video Recording
- **1080p @ 30fps** and **4K @ 30fps** (if supported by HAL)
- Recording indicator with timer
- Proper orientation metadata for correct gallery playback

### Camera Controls
- **Tap-to-focus** with visual focus ring indicator
- **Exposure compensation** (EV+/EV-) with real-time preview adjustment
- **AF/AE status indicator** showing focus and exposure state

### Gallery Integration
- Photos saved to `Pictures/FlashCam-Air3/` via **MediaStore** (scoped-storage safe)
- Immediate Gallery visibility without manual scanning
- Fallback to direct file write if MediaStore fails
- File naming: `FlashCam_YYYYMMDD_HHMMSS_<8MP|12MP|16MP>_full.jpg`

### Debug/Receipt System
- Toggle debug receipts on/off (default: off)
- After each capture, shows: mode, sensorOrientation, display upright rotation, requested vs actual dimensions, file path, file size
- Copy receipt to clipboard or export full capture log (last 50 captures)

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

# Build debug APK (no signing setup needed)
./gradlew assembleDebug

# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Signing Setup (for Release Builds)

Release builds require a signing keystore. **Never commit signing secrets to the repository.**

1. Copy the example config:
   ```bash
   cp keystore.properties.example keystore.properties
   ```

2. Edit `keystore.properties` with your actual values:
   ```properties
   storeFile=release-key.jks
   storePassword=your_actual_password
   keyAlias=your_key_alias
   keyPassword=your_actual_key_password
   ```

3. Generate a keystore (if you don't have one):
   ```bash
   keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias your_key_alias
   ```

4. Build the signed release APK:
   ```bash
   ./gradlew assembleRelease
   ```

The `keystore.properties` file and all `*.jks` / `*.keystore` files are in `.gitignore` and will never be committed. For CI/CD, set the same properties as environment variables.

> **Security note:** The v1.3 release inadvertently committed hard-coded keystore passwords to git history. Those have been **purged from all git history** using `git-filter-repo` in v1.5.1. If you cloned or forked before v1.5.1, please re-clone to get clean history. The original keystore should be considered compromised — generate a new one.

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

## Test Checklist (v1.6.0)

Use this checklist to verify all features after installing:

| # | Test | Expected Result |
|---|------|----------------|
| 1 | Launch app, grant CAMERA permission | Preview appears, status shows "Ready" |
| 2 | Preview matches real-world orientation | No 90° left shift — what you see matches reality |
| 3 | Tap shutter in 16MP | Receipt shows 4608×3456, image upright in Gallery |
| 4 | Capture result matches preview framing | Saved image has same framing and orientation as preview |
| 5 | Toggle DNG ON, capture in 16MP | DNG file saved alongside JPEG, ~31MB |
| 6 | Open saved JPEG in Gallery | Image is upright (not sideways) |
| 7 | Open saved DNG in Lightroom | DNG opens with correct orientation and white balance |
| 8 | Tap preview to focus | Yellow focus ring appears at tap point, AF indicator updates |
| 9 | Press EV+/EV- | Preview brightness changes, EV value updates |
| 10 | Toggle to 8MP, capture | Receipt shows ~3264×2448 or closest size |
| 11 | Switch to VIDEO mode, record 10s | Video saved, receipt shows file path and size |
| 12 | Tap shutter 10 times rapidly | No orange square, no crash, white flash each time |
| 13 | Leave idle 30 seconds | Status text stable, no flicker |
| 14 | Enable DBG, capture, tap COPY | Receipt text copied to clipboard |
| 15 | Tap EXPORT LOG | `flashcam_log.txt` saved to Pictures/FlashCam-Air3/ |
| 16 | No portrait mode/crop overlay exists | No LAND/PORT button, no crop overlay anywhere |

## Changelog

### v1.6.0 — Upright Preview + One-Mode Full Frame
- **Fixed preview rotation**: replaced rotation formula with the correct inverse `(degrees - sensorOrientation + 360) % 360`. Preview now matches real-world orientation on the Air3.
- **Removed portrait/landscape mode**: the app is now a one-mode full-frame camera. No crop, no portrait overlay. Users crop later if desired.
- **Simplified preview transform**: clean RectToRect + rotation approach, no multi-branch hacks.
- **One shared rotation helper** (`getDisplayUprightRotationDegrees()`) used for preview, JPEG rotation, and video orientation hint.
- **UI cleanup**: removed LAND/PORT button and indicators, mode display shows "16 MP FULL".
- **Deleted `CaptureFrameOverlayView`**: no longer needed.

### v1.5.1 — Orientation Fix + Portrait-as-Crop + Security
- Software pixel rotation (never trust JPEG_ORIENTATION)
- Portrait mode as center-crop to 3:4 from full landscape frame
- SENSOR_PIXEL_MODE crash protection (try/catch + reflection)
- MediaStore saving (scoped storage safe)
- Security: purged leaked keystore passwords from git history
- Removed 3MP option, removed quality slider (always Q100)

### v1.3 — Initial Release
- Full 16MP JPEG and 16.3MP RAW/DNG capture
- 8MP / 12MP / 16MP toggle
- DNG support
- Video recording (1080p/4K)
- Tap-to-focus, exposure compensation

## Known Limitations

1. **Max-res capture requires session switch** — switching between default and max-res modes requires closing and reopening the camera session, which causes a brief preview interruption (~0.5s)
2. **No autofocus during max-res capture** — some devices may not support AF in max-res mode; the app falls back gracefully
3. **DNG files are large** — ~31MB per capture at full sensor resolution
4. **Video is always default-mode** — video recording uses the default stream map (not max-res)
5. **SENSOR_PIXEL_MODE may not be supported on all devices** — all access is wrapped in try/catch; the app falls back gracefully to default mode if the API throws.
6. **INMO Air3 specific** — the app was designed for and tested on the INMO Air3. Other devices with `ULTRA_HIGH_RESOLUTION_SENSOR` may work but are untested.

## How It Works

The INMO Air3's camera HAL (Hardware Abstraction Layer) exposes two stream configuration maps:

1. **Default map** (`SCALER_STREAM_CONFIGURATION_MAP`): Max JPEG 2048×1536 (3.1MP)
2. **Max-res map** (`SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`): Max JPEG 4608×3456 (15.9MP)

Standard camera apps only query the default map. FlashCam-Air3 queries the max-res map and uses `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION` in both the session configuration and capture requests to access the full sensor resolution.

### Orientation Handling

The Air3 is a landscape-locked device with `sensorOrientation = 270°`. The correct rotation to make the sensor buffer upright on the display is:

```
uprightDegrees = (displayDegrees - sensorOrientation + 360) % 360
```

This single formula is used for:
- **Preview transform** — rotating and scaling the TextureView buffer to match reality
- **JPEG pixel rotation** — physically rotating the captured JPEG pixels before saving
- **Video orientation hint** — setting `MediaRecorder.setOrientationHint()`

`JPEG_ORIENTATION` is always set to 0 (the sensor encoder is not trusted to rotate). All rotation is done in software after capture.

## Troubleshooting

| Issue | Solution |
|-------|---------|
| "Camera open failed" | Close all other camera apps, reboot the Air3, try again |
| Max-res capture times out | Ensure no other app is using the camera; reboot |
| Images appear sideways | Update to v1.6.0; the inverse rotation formula should fix this |
| DNG won't open in editor | Ensure you're using a DNG-compatible editor (Lightroom, RawTherapee, etc.) |
| App crashes on launch | Ensure Camera permission is granted in Settings > Apps > FlashCam-Air3 |
| Preview shows black bars | Normal — letterbox/pillarbox areas are outside the 4:3 sensor frame |
| "SENSOR_PIXEL_MODE not supported" | The HAL may not support this API; the app falls back to default mode |

## License

MIT License — see [LICENSE](LICENSE) for details.

## Credits

- Developed with assistance from [Manus AI](https://manus.im) and [ChatGPT (OpenAI)](https://openai.com)
- Inspired by the INMO Air3 community's discovery that the 16MP sensor was firmware-locked to 3MP
- Camera2 max-res API documentation: [Android Developer Docs](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
