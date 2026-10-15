# pyxpo-image-picker

Photo library picker for pyxpo apps (expo-image-picker shape).

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
```

Permissions and Info.plist keys: none (uses the system photo picker).

## What is in the package

- `pyxpo_image_picker/__init__.py`: the Python API.
- `pyxpo_image_picker/pyxpo-module.json`: autolink metadata (bridge name, Swift and Kotlin classes, Android namespace and dependencies, Info.plist keys).
- `pyxpo_image_picker/ios/`: Swift sources compiled into the iOS app.
- `pyxpo_image_picker/android/src/main/`: Kotlin sources and the manifest merged into the Android app.
