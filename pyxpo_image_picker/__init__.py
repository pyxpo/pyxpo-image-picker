"""Photo library picker (expo-image-picker shape). Picked files are copied into the app cache, so `uri` is a local path."""
from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional, Union

from pyxpo.module_api import NotSupportedError, call, require_linked, snake_aliases

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


@snake_aliases
class ImagePicker:
    @staticmethod
    async def launchImageLibraryAsync(mediaTypes: Union[str, Iterable[str], None] = "images",
                                      allowsMultipleSelection: bool = False, selectionLimit: int = 0) -> Dict[str, Any]:
        """Returns `{"canceled": bool, "assets": [asset]}`. An asset is
        `{"uri", "type": "image"|"video", "width", "height", "fileSize", "mimeType", "fileName"}`; videos also carry
        `"duration"` in milliseconds. `selectionLimit` 0 means no limit and only applies to multiple selection."""
        require_linked("ImagePicker", "image-picker", package="pyxpo-image-picker")
        kinds = _media(mediaTypes)
        limit = int(selectionLimit)
        if limit < 0:
            raise ValueError("selectionLimit must be 0 (no limit) or positive")
        multiple = bool(allowsMultipleSelection) and limit != 1  # a limit of 1 is a single pick
        res = await call("ImagePicker", "pick", multiple, limit if multiple else 0, kinds, reply=True)
        assets = list((res or {}).get("assets") or [])
        return {"canceled": not assets, "assets": assets}
