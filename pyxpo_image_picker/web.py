"""Web backend for `pyxpo build web`: the browser's file chooser stands in for the photo library. Picked files are
copied into the app's in-memory file system, so their paths work with Image and other modules."""
from __future__ import annotations

from typing import Any, Dict, List

from pyxpo.renderers.web.browser import choose_files, save_file

_ACCEPT = {"images": "image/*", "videos": "video/*", "all": "image/*,video/*"}
FOLDER = "/tmp/pyxpo/picked"


async def _size(file: Any) -> List[int]:
    import js  # type: ignore[import-not-found]

    try:
        bmp = await js.createImageBitmap(file)  # applies EXIF orientation, like the native pickers
        w, h = int(bmp.width), int(bmp.height)
        bmp.close()
        return [w, h]
    except Exception:  # noqa: BLE001  formats the browser cannot decode (HEIC on most browsers)
        return [0, 0]


async def _asset(file: Any) -> Dict[str, Any]:
    mime = str(file.type or "") or "application/octet-stream"
    video = mime.startswith("video/")
    w, h = [0, 0] if video else await _size(file)
    path = await save_file(file, FOLDER)
    return {"uri": path, "type": "video" if video else "image", "width": w, "height": h,
            "fileSize": int(file.size), "mimeType": mime, "fileName": str(file.name)}


async def pick(multiple: bool, limit: int, kinds: str) -> Dict[str, Any]:
    files = await choose_files(_ACCEPT.get(kinds, _ACCEPT["images"]), multiple)
    if multiple and limit:
        files = files[:limit]
    return {"assets": [await _asset(f) for f in files]}


def call(method: str, args: List[Any]) -> Any:
    if method == "pick":
        return pick(*args)
    if method == "camera_permission":
        return {"status": "denied", "canAskAgain": False}
    if method == "camera":
        raise RuntimeError("the camera is not available on web")
    raise ValueError("unknown ImagePicker method %r" % method)
