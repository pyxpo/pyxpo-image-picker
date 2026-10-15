"""Photo library picker and camera (expo-image-picker shape). Picked and captured files are copied into the app cache,
so `uri` is a local path."""
from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional, Union

from pyxpo.module_api import (NotSupportedError, call, permission_response, require_linked, require_supported,
                              snake_aliases)

__version__ = "0.1.0"

_KINDS = ("images", "videos")


def _media(media_types: Union[str, Iterable[str], None]) -> str:
    """Normalizes expo `mediaTypes` ("images", "videos", or a list of them) to "images", "videos" or "all"."""
    if media_types is None:
        return "images"
    items: List[str] = [media_types] if isinstance(media_types, str) else list(media_types)
    if not items:
        raise ValueError("mediaTypes must not be empty")
    for m in items:
        if m == "livePhotos":
            raise NotSupportedError("mediaTypes 'livePhotos' is not supported yet")
        if m not in _KINDS:
            raise ValueError("mediaTypes must be 'images' or 'videos' (or a list of them), got %r" % (m,))
    return "all" if len(set(items)) > 1 else items[0]


def _linked() -> None:
    require_linked("ImagePicker", "image-picker", package="pyxpo-image-picker")


def _result(res: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    assets = list((res or {}).get("assets") or [])
    return {"canceled": not assets, "assets": assets}


def _permission(res: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    res = res or {}
    return permission_response(str(res.get("status", "denied")), bool(res.get("canAskAgain", False)))


@snake_aliases
class ImagePicker:
    @staticmethod
    async def launchImageLibraryAsync(mediaTypes: Union[str, Iterable[str], None] = "images",
                                      allowsMultipleSelection: bool = False, selectionLimit: int = 0) -> Dict[str, Any]:
        """Returns `{"canceled": bool, "assets": [asset]}`. An asset is
        `{"uri", "type": "image"|"video", "width", "height", "fileSize", "mimeType", "fileName"}`; videos also carry
        `"duration"` in milliseconds. `selectionLimit` 0 means no limit and only applies to multiple selection."""
        _linked()
        kinds = _media(mediaTypes)
        limit = int(selectionLimit)
        if limit < 0:
            raise ValueError("selectionLimit must be 0 (no limit) or positive")
        multiple = bool(allowsMultipleSelection) and limit != 1  # a limit of 1 is a single pick
        res = await call("ImagePicker", "pick", multiple, limit if multiple else 0, kinds, reply=True)
        return _result(res)

    @staticmethod
    async def launchCameraAsync(mediaTypes: Union[str, Iterable[str], None] = "images",
                                videoMaxDuration: float = 0) -> Dict[str, Any]:
        """Takes a photo or records a video with the system camera UI. Returns the same shape as
        `launchImageLibraryAsync` with at most one asset. `videoMaxDuration` is in seconds (0 means no limit).
        Asks for camera permission when needed and fails if it is denied or the device has no camera
        (the iOS simulator has none). Photo and video in one capture (`["images", "videos"]`) is iOS only."""
        _linked()
        kinds = _media(mediaTypes)
        if kinds == "all":
            require_supported("launchCameraAsync with both images and videos", android=False)
        if isinstance(videoMaxDuration, bool) or not isinstance(videoMaxDuration, (int, float)) or videoMaxDuration < 0:
            raise ValueError("videoMaxDuration must be a number of seconds >= 0 (0 means no limit)")
        res = await call("ImagePicker", "camera", kinds, float(videoMaxDuration), reply=True)
        return _result(res)

    @staticmethod
    async def getCameraPermissionsAsync() -> Dict[str, Any]:
        """Expo `PermissionResponse` for the camera, without asking. On Android, an app that does not declare
        `android.permission.CAMERA` needs no permission for the system camera, so this reports `granted`."""
        _linked()
        return _permission(await call("ImagePicker", "camera_permission", False, reply=True))

    @staticmethod
    async def requestCameraPermissionsAsync() -> Dict[str, Any]:
        """Asks for camera permission when it is still undetermined. Returns an expo `PermissionResponse`."""
        _linked()
        return _permission(await call("ImagePicker", "camera_permission", True, reply=True))
