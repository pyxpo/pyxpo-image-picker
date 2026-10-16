"""Desktop backend (Mac preview window and the native Windows app): the system file dialog stands in for the photo
library."""
from __future__ import annotations

import mimetypes
import os
import subprocess
import sys
from typing import Any, Dict, List

_TYPES = {"images": '"public.image"', "videos": '"public.movie"', "all": '"public.image", "public.movie"'}
_WIN_FILTERS = {"images": "Images|*.png;*.jpg;*.jpeg;*.gif;*.bmp;*.webp;*.heic;*.heif",
                "videos": "Videos|*.mp4;*.mov;*.m4v;*.avi;*.wmv;*.mkv"}
_WIN_FILTERS["all"] = "Photos and videos|" + _WIN_FILTERS["images"].split("|")[1] + ";" + _WIN_FILTERS["videos"].split("|")[1]


def _size(path: str) -> List[int]:
    try:
        from PIL import Image, ImageOps
        with Image.open(path) as img:
            w, h = img.size
            if img.getexif().get(0x0112) in (5, 6, 7, 8):
                w, h = h, w
            return [w, h]
    except Exception:  # noqa: BLE001
        pass
    try:  # HEIC and others Pillow cannot read
        out = subprocess.run(["sips", "-g", "pixelWidth", "-g", "pixelHeight", path], capture_output=True, text=True).stdout
        vals = [int(ln.split(":")[1]) for ln in out.splitlines() if "pixel" in ln]
        return vals if len(vals) == 2 else [0, 0]
    except Exception:  # noqa: BLE001
        return [0, 0]


def _asset(path: str) -> Dict[str, Any]:
    mime = mimetypes.guess_type(path)[0] or ("image/heic" if path.lower().endswith((".heic", ".heif")) else "")
    video = mime.startswith("video/")
    w, h = [0, 0] if video else _size(path)
    return {"uri": path, "type": "video" if video else "image", "width": w, "height": h,
            "fileSize": os.path.getsize(path), "mimeType": mime or "application/octet-stream",
            "fileName": os.path.basename(path)}


def _pick_windows(multiple: bool, kinds: str) -> List[str]:
    """The WinForms open dialog, in its own PowerShell process so it never blocks the app's UI thread."""
    script = ("Add-Type -AssemblyName System.Windows.Forms;"
              "$o = New-Object System.Windows.Forms.Form -Property @{TopMost=$true};"
              "$d = New-Object System.Windows.Forms.OpenFileDialog;"
              "$d.Filter = '%s'; $d.Multiselect = $%s;"
              "if ($d.ShowDialog($o) -eq 'OK') { $d.FileNames -join [char]10 }") % (
        _WIN_FILTERS.get(kinds, _WIN_FILTERS["images"]), "true" if multiple else "false")
    r = subprocess.run(["powershell", "-NoProfile", "-STA", "-Command", script], capture_output=True, text=True,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    return [p for p in r.stdout.splitlines() if p.strip()] if r.returncode == 0 else []


def pick(multiple: bool, limit: int, kinds: str) -> Dict[str, Any]:
    if sys.platform == "win32":
        paths = _pick_windows(multiple, kinds)
        return {"assets": [_asset(p) for p in (paths[:limit] if multiple and limit else paths)]}
    if sys.platform != "darwin":
        raise RuntimeError("the desktop image picker needs macOS or Windows")
    script = ('set fs to choose file with prompt "Choose %s" of type {%s}%s\n'
              'set out to ""\n'
              'repeat with f in (fs as list)\n set out to out & POSIX path of f & linefeed\nend repeat\n'
              'return out') % ("photos" if multiple else "a photo", _TYPES.get(kinds, _TYPES["images"]),
                               " with multiple selections allowed" if multiple else "")
    r = subprocess.run(["osascript", "-e", 'tell application "System Events" to activate', "-e", script],
                       capture_output=True, text=True)
    if r.returncode != 0:  # user canceled (error -128)
        return {"assets": []}
    paths = [p for p in r.stdout.splitlines() if p.strip()]
    if multiple and limit:
        paths = paths[:limit]
    return {"assets": [_asset(p) for p in paths]}


def call(method: str, args: List[Any]) -> Any:
    if method == "pick":
        return pick(*args)
    if method == "camera_permission":
        return {"status": "denied", "canAskAgain": False}
    if method == "camera":
        raise RuntimeError("no camera on desktop")
    raise ValueError("unknown ImagePicker method %r" % method)
