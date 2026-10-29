from __future__ import annotations

import asyncio

import pytest

from pyxpo.apis import _bridge
from pyxpo.module_api import NotSupportedError
from pyxpo_image_picker import ImagePicker


class FakeApp:
    """Records native calls and answers from `handlers[(module, method)]`."""

    loop = None

    def __init__(self, handlers):
        self.handlers = handlers
        self.calls = []

    def call(self, module, method, *args, reply=False):
        self.calls.append((module, method, args))
        fut = asyncio.get_event_loop().create_future()
        out = self.handlers[(module, method)](*args)
        if isinstance(out, Exception):
            fut.set_exception(out)
        else:
            fut.set_result(out)
        return fut


def run(handlers, coro_fn):
    app = FakeApp(handlers)
    old = _bridge.get_app()
    _bridge.set_app(app)
    try:
        return asyncio.run(coro_fn()), app
    finally:
        _bridge.set_app(old)


def test_picker_media_types_and_limit_normalization():
    h = {("ImagePicker", "pick"): lambda m, l, k: {"assets": [{"uri": "/v.mov", "type": "video"}]}}
    cases = [
        (dict(mediaTypes=["images", "videos"], allowsMultipleSelection=True), (True, 0, "all")),
        (dict(mediaTypes="videos"), (False, 0, "videos")),
        (dict(allowsMultipleSelection=True, selectionLimit=1), (False, 0, "images")),  # a limit of 1 is a single pick
        (dict(selectionLimit=5), (False, 0, "images")),  # limit means nothing without multiple selection
    ]
    for kw, want in cases:
        _, app = run(h, lambda: ImagePicker.launchImageLibraryAsync(**kw))
        assert app.calls[0][2] == want
    for kw, exc in ((dict(mediaTypes="audio"), ValueError), (dict(mediaTypes=[]), ValueError),
                    (dict(mediaTypes="livePhotos"), NotSupportedError), (dict(selectionLimit=-1), ValueError)):
        with pytest.raises(exc):
            run(h, lambda: ImagePicker.launchImageLibraryAsync(**kw))


def test_picker_library_shape():
    out, app = run({("ImagePicker", "pick"): lambda multi, limit, kinds: {"assets": [{"uri": "/x.jpg"}]}},
                   lambda: ImagePicker.launch_image_library_async(allowsMultipleSelection=True, selectionLimit=3))
    assert out == {"canceled": False, "assets": [{"uri": "/x.jpg"}]}
    assert app.calls[0][2] == (True, 3, "images")
    out, _ = run({("ImagePicker", "pick"): lambda m, l, k: {"assets": []}}, lambda: ImagePicker.launchImageLibraryAsync())
    assert out == {"canceled": True, "assets": []}


def test_camera_launch_args_and_shape(monkeypatch):
    from pyxpo.apis import _env
    h = {("ImagePicker", "camera"): lambda kinds, secs: {"assets": [{"uri": "/c.jpg", "type": "image"}]}}
    out, app = run(h, lambda: ImagePicker.launchCameraAsync())
    assert out == {"canceled": False, "assets": [{"uri": "/c.jpg", "type": "image"}]}
    assert app.calls[0][:3] == ("ImagePicker", "camera", ("images", 0.0))
    _, app = run(h, lambda: ImagePicker.launch_camera_async(mediaTypes="videos", videoMaxDuration=15))
    assert app.calls[0][2] == ("videos", 15.0)
    out, _ = run({("ImagePicker", "camera"): lambda k, s: {"assets": []}}, lambda: ImagePicker.launchCameraAsync())
    assert out == {"canceled": True, "assets": []}
    for kw, exc in ((dict(mediaTypes="audio"), ValueError), (dict(videoMaxDuration=-1), ValueError),
                    (dict(videoMaxDuration="10"), ValueError), (dict(videoMaxDuration=True), ValueError)):
        with pytest.raises(exc):
            run({}, lambda: ImagePicker.launchCameraAsync(**kw))
    # photo and video in one capture: iOS only, refused on Android before the native call
    monkeypatch.setattr(_env, "current", _env.Env({"os": "android", "modules": ["ImagePicker"]}))
    with pytest.raises(NotSupportedError):
        run({}, lambda: ImagePicker.launchCameraAsync(mediaTypes=["images", "videos"]))
    monkeypatch.setattr(_env, "current", _env.Env({"os": "ios", "modules": ["ImagePicker"]}))
    _, app = run(h, lambda: ImagePicker.launchCameraAsync(mediaTypes=["images", "videos"]))
    assert app.calls[0][2] == ("all", 0.0)


def test_camera_permissions_expo_shape():
    h = {("ImagePicker", "camera_permission"): lambda ask: {"status": "granted" if ask else "undetermined", "canAskAgain": True}}
    out, app = run(h, lambda: ImagePicker.getCameraPermissionsAsync())
    assert out == {"status": "undetermined", "granted": False, "canAskAgain": True, "expires": "never"}
    assert app.calls[0][2] == (False,)
    out, app = run(h, lambda: ImagePicker.request_camera_permissions_async())
    assert out == {"status": "granted", "granted": True, "canAskAgain": True, "expires": "never"}
    assert app.calls[0][2] == (True,)
