# pyxpo-image-picker

Photo library picker and camera for pyxpo apps (expo-image-picker shape).

This is an optional [pyxpo](https://github.com/ivishalchaubey/pyxpo) native module. Apps that do not
list it carry none of its code, permissions or dependencies.

## Install

```bash
pip install pyxpo-image-picker
```

Then link it into your app in `pyproject.toml`:

```toml
[tool.pyxpo]
modules = ["image-picker"]
```

and rebuild (`pyxpo run ios`, `pyxpo build android`, ...). The build copies the module's Swift and Kotlin
sources into the native project and its Python package into the app.

## Use

```python
from pyxpo_image_picker import ImagePicker

res = await ImagePicker.launchImageLibraryAsync(allowsMultipleSelection=True, selectionLimit=5)
shot = await ImagePicker.launchCameraAsync(mediaTypes="images")   # or "videos", videoMaxDuration=30
perm = await ImagePicker.requestCameraPermissionsAsync()          # {"status", "granted", "canAskAgain", "expires"}
```

Permissions and Info.plist keys:

- Library: none (uses the system photo picker).
- Camera, iOS: `NSCameraUsageDescription` and, for video sound, `NSMicrophoneUsageDescription`. Set the text with
  `[tool.pyxpo.module_options.image-picker] cameraUsageDescription = "..."` / `microphoneUsageDescription = "..."`.
  On a device without a camera (simulators before iOS 27) `launchCameraAsync` fails with a clear error.
- Camera, Android: none. The module uses the system camera app (`ACTION_IMAGE_CAPTURE` / `ACTION_VIDEO_CAPTURE`), which
  needs no permission, so it does not declare `CAMERA`. Android enforces `CAMERA` for those intents only when the app
  declares it; if your app (or another module) does, `launchCameraAsync` asks for it first and the permission functions
  report its real state. Photo and video in one capture (`mediaTypes=["images", "videos"]`) is iOS only and raises
  `NotSupportedError` on Android.
- Android may kill the app while the camera app is in front (low memory). That capture is lost (the call never
  returns in the new process); the leftover file in the cache is removed on the next capture.

## What is in the package

- `pyxpo_image_picker/__init__.py`: the Python API.
- `pyxpo_image_picker/pyxpo-module.json`: autolink metadata (bridge name, Swift and Kotlin classes, Android namespace and dependencies, Info.plist keys).
- `pyxpo_image_picker/ios/`: Swift sources compiled into the iOS app.
- `pyxpo_image_picker/android/src/main/`: Kotlin sources and the manifest merged into the Android app.
