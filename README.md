# pyxpo-image-picker

Pick photos and videos from the library, or capture one with the system camera, in [pyxpo](https://github.com/pyxpo/pyxpo)
apps written in Python. The API follows [expo-image-picker](https://docs.expo.dev/versions/latest/sdk/imagepicker/).
Picked and captured files are copied into the app cache, so every `uri` is a plain local path you can show in an `Image`
or hand to other modules.

<p align="center">
  <img src="https://raw.githubusercontent.com/pyxpo/pyxpo/main/packages/image-picker/docs/images/hero.png" width="820" alt="The system photo picker on Android and the result of a picked photo on iOS">
</p>

```python
from pyxpo_image_picker import ImagePicker

res = await ImagePicker.launch_image_library_async(media_types="images")
if not res["canceled"]:
    print(res["assets"][0]["uri"])
```

## Features

- **Library picker** for images, videos or both, single or multiple selection with a limit.
- **System camera** for a photo or a video, with a maximum video length.
- **No library permission:** it uses the system photo pickers (PHPicker on iOS, the Photo Picker or document picker on Android).
- **Local files:** every asset is copied to the app cache with its size, type, name and (for videos) duration.
- **Same on every target:** on the web a file chooser, on Mac and Windows the system open dialog.
- **Typed:** ships `py.typed`. camelCase (`launchImageLibraryAsync`) and snake_case names both work.

## Contents

| If you are... | Start here |
| --- | --- |
| Letting people pick a photo | [Quick start](#quick-start) |
| Picking many photos or videos | [Library](#library) |
| Taking a photo or video | [Camera](#camera) |
| Handling the camera permission | [Permissions](#permissions) |
| Reading the result | [The result](#the-result) |
| Exact signatures | [API reference](#api-reference) · [Errors](#errors) |
| Seeing it in a real app | [Recipe: profile photo](#recipe-profile-photo) |
| Stuck | [Testing](#testing) · [Troubleshooting](#troubleshooting) · [FAQ](#faq) |

## Platform support

| Target | What works |
| --- | --- |
| Android, API 24+ | Library picker and system camera app. No permission declared. |
| iOS 15+ | PHPicker library and the system camera. The Simulator has no camera. |
| Web page | The browser file chooser. No camera (`launch_camera_async` fails). |
| Mac preview window and Windows app | The system open dialog. No camera. |

Requires Python 3.9+ and `pyxpo>=0.1.0a1`.

## Install

```sh
pip install pyxpo-image-picker
```

```toml
[tool.pyxpo]
modules = ["image-picker"]

# Optional: the iOS camera and microphone prompt texts.
[tool.pyxpo.module_options.image-picker]
cameraUsageDescription = "Take a profile photo."
microphoneUsageDescription = "Record sound with your video."
```

Rebuild the app (`pyxpo run android` or `pyxpo run ios`).

| Option | Info.plist key | Default |
| --- | --- | --- |
| `cameraUsageDescription` | `NSCameraUsageDescription` | "Take photos and videos with the camera." |
| `microphoneUsageDescription` | `NSMicrophoneUsageDescription` | "Record sound with the videos you take." |

## Quick start

```python
from pyxpo import Button, Image, Text, View, component, use_async, use_state
from pyxpo_image_picker import ImagePicker


@component
def Avatar():
    taps, set_taps = use_state(0)
    path, set_path = use_state("")
    error, set_error = use_state("")

    async def pick():
        if taps == 0:
            return
        try:
            res = await ImagePicker.launch_image_library_async(media_types="images")
        except Exception as e:  # noqa: BLE001
            set_error(str(e))
            return
        if not res["canceled"]:
            set_path(res["assets"][0]["uri"])

    use_async(pick, [taps])

    return View(
        Image(source=path, style={"width": 160, "height": 160}) if path else Text("No photo yet"),
        Button("Choose a photo", lambda: set_taps(taps + 1)),
        Text(error),
    )
```

> **Tip:** A button handler cannot simply `await`. Bump a counter in state and run the awaiting function from
> `use_async(fn, [counter])`. A `lambda: some_coroutine()` only builds the coroutine, and nothing awaits it.

<p align="center">
  <img src="https://raw.githubusercontent.com/pyxpo/pyxpo/main/packages/image-picker/docs/images/result.png" width="720" alt="The picked photo and its details on Android and iOS">
</p>

## Library

```python
res = await ImagePicker.launch_image_library_async(
    media_types=["images", "videos"],   # "images" (default), "videos", or a list
    allows_multiple_selection=True,
    selection_limit=5,                  # 0 = no limit; only used with multiple selection
)
```

- A `selection_limit` of 1 is treated as a single pick.
- `selection_limit` must be 0 or more, or a `ValueError` is raised.
- `media_types` must be `"images"` and/or `"videos"`. `"livePhotos"` raises `NotSupportedError`.
- Cancelling is not an error: you get `{"canceled": True, "assets": []}`.

## Camera

```python
photo = await ImagePicker.launch_camera_async(media_types="images")
clip = await ImagePicker.launch_camera_async(media_types="videos", video_max_duration=30)
```

This opens the system camera UI and returns at most one asset. On Android it is the camera app, which needs no
permission. On iOS the first call asks for camera access. Photo and video in one capture
(`media_types=["images", "videos"]`) works on iOS only, and raises `NotSupportedError` on Android.
`video_max_duration` is in seconds (0 means no limit). A negative or non-number value raises `ValueError`.

The iOS Simulator has no camera, so `launch_camera_async` fails there with a clear error. Use a real device.

## Permissions

The library picker needs none. For the camera:

```python
perm = await ImagePicker.get_camera_permissions_async()       # does not ask
perm = await ImagePicker.request_camera_permissions_async()   # asks if undetermined
```

Both return `{"status", "granted", "canAskAgain", "expires"}`. On Android an app that does not declare `CAMERA` needs
nothing for the system camera, so this reports `granted`. If your app (or another module such as `pyxpo-camera`)
declares `CAMERA`, Android enforces it and `launch_camera_async` asks first.

## The result

```python
{"canceled": False, "assets": [
    {"uri": "/path/in/cache.jpg", "type": "image", "width": 4032, "height": 3024,
     "fileSize": 1873422, "mimeType": "image/jpeg", "fileName": "IMG_0042.jpg"},
]}
```

| Field | Notes |
| --- | --- |
| `uri` | A local path in the app cache. |
| `type` | `"image"` or `"video"`. |
| `width`, `height` | Pixels, after EXIF rotation. `0` for videos, and for formats the browser cannot decode. |
| `fileSize` | Bytes. |
| `mimeType`, `fileName` | From the file. |
| `duration` | Videos only, in milliseconds. |

## Errors

| Error | When | What to do |
| --- | --- | --- |
| `ValueError` | Bad `media_types`, `selection_limit` or `video_max_duration`. | Fix the argument. |
| `NotSupportedError` | `"livePhotos"`, or images and videos in one camera capture on Android. | Pick one kind. |
| `ModuleNotLinkedError` (from `pyxpo.module_api`) | Built without `"image-picker"` in `modules`. | Add it and rebuild. |
| Camera error (no camera, permission denied) | iOS Simulator, web, desktop, or the user said no. | Catch it, offer the library instead, and send the user to Settings if `canAskAgain` is `False`. |
| A cancelled pick | The user closed the picker. | Not an error: `canceled` is `True`. |

## Config

Only the two iOS prompt texts above. Android needs no manifest permission. The module adds a private file provider so
the camera app can write into your cache.

## Web and desktop

On the web page the browser file chooser opens, and files are copied into the app's in-memory file system under
`/tmp/pyxpo/picked`. On the Mac preview window and the Windows app the system open dialog is used. The camera is not
available on any of them: `launch_camera_async` raises, and the camera permission reads `denied`.

## Recipe: profile photo

Pick or take a photo, falling back to the library when there is no camera.

```python
from pyxpo import Button, Image, Text, View, component, use_async, use_state
from pyxpo_image_picker import ImagePicker


@component
def ProfilePhoto():
    want, set_want = use_state("")      # "" | "library:1" | "camera:1" ...
    path, set_path = use_state("")
    note, set_note = use_state("")

    async def run():
        if not want:
            return
        res = None
        if want.startswith("camera"):
            try:
                res = await ImagePicker.launch_camera_async("images")
            except Exception:  # noqa: BLE001  no camera, or denied
                set_note("No camera, choose from the library")
        if res is None:
            res = await ImagePicker.launch_image_library_async("images")
        if not res["canceled"]:
            set_path(res["assets"][0]["uri"])

    use_async(run, [want])

    def start(kind):
        set_want("%s:%d" % (kind, int(want.split(":")[1]) + 1 if want else 1))

    return View(
        Image(source=path, style={"width": 120, "height": 120, "border_radius": 60}) if path else Text("No photo"),
        Button("Take a photo", lambda: start("camera")),
        Button("Choose from library", lambda: start("library")),
        Text(note),
    )
```

## Production checklist

- [ ] `cameraUsageDescription` (and the microphone text if you record video) explains the use.
- [ ] You treat `canceled` as a normal outcome.
- [ ] You catch camera errors and fall back to the library.
- [ ] You copy files you want to keep out of the cache, which the system may clear.
- [ ] Large photos are shrunk with `pyxpo-image-manipulator` before upload.
- [ ] You tested the camera on a real device, not only the Simulator.
- [ ] The web and desktop builds do not show a "Take a photo" button, or handle its failure.

## Testing

Fake the bridge so tests run without a device:

```python
import asyncio

import pyxpo_image_picker as mod
from pyxpo_image_picker import ImagePicker


def test_pick(monkeypatch):
    async def fake_call(module, method, *args, **kw):
        assert (module, method) == ("ImagePicker", "pick")
        return {"assets": [{"uri": "/tmp/a.jpg", "type": "image", "width": 1, "height": 1}]}

    monkeypatch.setattr(mod, "call", fake_call)
    monkeypatch.setattr(mod, "require_linked", lambda *a, **k: None)
    res = asyncio.run(ImagePicker.launch_image_library_async())
    assert res["canceled"] is False and res["assets"][0]["uri"] == "/tmp/a.jpg"


def test_cancel(monkeypatch):
    async def fake_call(*a, **k):
        return {"assets": []}

    monkeypatch.setattr(mod, "call", fake_call)
    monkeypatch.setattr(mod, "require_linked", lambda *a, **k: None)
    assert asyncio.run(ImagePicker.launch_image_library_async())["canceled"] is True
```

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Camera fails on the iOS Simulator | It has no camera. Use a device. |
| `ValueError: selectionLimit` | Pass 0 or a positive number. |
| `NotSupportedError` for `livePhotos` | Not supported yet. Use `"images"`. |
| Width and height are 0 | Videos have no size here, and browsers cannot decode HEIC. |
| The app restarted during the Android camera | Android can kill the app while the camera is in front. That capture is lost. The cache file is removed on the next capture. |
| `ModuleNotLinkedError` | Add `"image-picker"` to `modules` and rebuild. |

## FAQ

**Does it need the photo-library permission?** No. The system pickers return only what the user selects.

**Where are the files?** In the app cache (a local path). Copy them if you need to keep them.

**Can I pick videos?** Yes, `media_types="videos"`; the asset has a `duration` in milliseconds.

**Can I crop or edit?** No. Use `pyxpo-image-manipulator` afterwards.

## API reference

### `ImagePicker`

| Method (camelCase name) | Returns |
| --- | --- |
| `launch_image_library_async(media_types="images", allows_multiple_selection=False, selection_limit=0)` | `{"canceled", "assets"}` |
| `launch_camera_async(media_types="images", video_max_duration=0)` | `{"canceled", "assets"}` (at most one asset) |
| `get_camera_permissions_async()` | Permission response, without asking. |
| `request_camera_permissions_async()` | Permission response, asks when undetermined. |

## License

MIT
