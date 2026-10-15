package dev.pyxpo.modules.imagepicker

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.pyxpo.moduleapi.PxModule
import dev.pyxpo.moduleapi.PxModuleContext
import dev.pyxpo.moduleapi.PxReply
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Photo library picker and camera. Uses the system photo picker on Android 13+ (no permission) and ACTION_GET_CONTENT
 * before; the camera is the system camera app (ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE) writing through
 * [PxCameraFileProvider]. Results are copied into the app cache with their original bytes, so `uri` is a local path.
 */
class ImagePickerModule : PxModule() {
    override val name = "ImagePicker"
    @Volatile private var busy = false

    override fun call(ctx: PxModuleContext, method: String, args: JSONArray, reply: PxReply) {
        when (method) {
            "pick" -> pick(ctx, args, reply)
            "camera" -> camera(ctx, args, reply)
            "camera_permission" -> cameraPermission(ctx, args.optBoolean(0, false), reply)
            else -> reply(null, "unknown native call ImagePicker.$method")
        }
    }

    private fun pick(ctx: PxModuleContext, args: JSONArray, reply: PxReply) {
        if (busy) { reply(null, "a picker is already open"); return }
        val multiple = args.optBoolean(0, false)
        val limit = args.optInt(1, 0)
        val kinds = args.optString(2, "images")
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                // no type = photos and videos
                if (kinds == "images") type = "image/*" else if (kinds == "videos") type = "video/*"
                if (multiple) {
                    val max = MediaStore.getPickImagesMaxLimit()
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, if (limit in 2..max) limit else max)
                }
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                if (kinds == "all") {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                } else {
                    type = if (kinds == "videos") "video/*" else "image/*"
                }
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
        }
        busy = true
        try {
            ctx.startForResult(intent) { code, data ->
                busy = false
                if (code != Activity.RESULT_OK || data == null) { reply(mapOf("assets" to emptyList<Any>()), null); return@startForResult }
                val uris = ArrayList<Uri>()
                data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                val capped = if (multiple && limit > 0) uris.take(limit) else uris.take(if (multiple) uris.size else 1)
                val cr = ctx.activity.contentResolver
                val dir = File(ctx.activity.cacheDir, "pyxpo-picked").apply { mkdirs() }
                executor.execute {
                    try {
                        reply(mapOf("assets" to capped.map { copy(cr, it, dir) }), null)
                    } catch (e: Throwable) {
                        reply(null, e.message ?: e.toString())
                    }
                }
            }
        } catch (e: Throwable) {
            busy = false
            reply(null, e.message ?: e.toString())
        }
    }

    // camera

    /** CAMERA is only enforced for apps that declare it; this module does not, so normally no permission is needed. */
    private fun declaresCamera(a: Activity): Boolean = try {
        @Suppress("DEPRECATION")
        a.packageManager.getPackageInfo(a.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    } catch (e: Exception) { false }

    private fun cameraState(a: Activity): Map<String, Any> {
        val p = Manifest.permission.CAMERA
        if (!declaresCamera(a) || a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) {
            return mapOf("status" to "granted", "canAskAgain" to true)
        }
        if (!prefs(a).getBoolean(p, false)) return mapOf("status" to "undetermined", "canAskAgain" to true)
        return mapOf("status" to "denied", "canAskAgain" to a.shouldShowRequestPermissionRationale(p))
    }

    private fun prefs(a: Activity) = a.getSharedPreferences("pyxpo.permissions", Context.MODE_PRIVATE)

    /** Asks for CAMERA when the app declares it and it is not granted yet, then reports the state. */
    private fun ensureCamera(ctx: PxModuleContext, onState: (Map<String, Any>) -> Unit) {
        val a = ctx.activity
        val p = Manifest.permission.CAMERA
        if (!declaresCamera(a) || a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) { onState(cameraState(a)); return }
        ctx.requestPermissions(arrayOf(p)) { _ ->
            prefs(a).edit().putBoolean(p, true).apply()
            onState(cameraState(a))
        }
    }

    private fun cameraPermission(ctx: PxModuleContext, ask: Boolean, reply: PxReply) {
        val state = cameraState(ctx.activity)
        if (!ask || state["status"] == "granted" || state["canAskAgain"] != true) { reply(state, null); return }
        ensureCamera(ctx) { reply(it, null) }
    }

    private fun camera(ctx: PxModuleContext, args: JSONArray, reply: PxReply) {
        if (busy) { reply(null, "a picker is already open"); return }
        val a = ctx.activity
        if (!a.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) { reply(null, "this device has no camera"); return }
        val kinds = args.optString(0, "images")
        if (kinds == "all") { reply(null, "Android cannot capture photos and videos in one camera session"); return }
        val video = kinds == "videos"
        val maxSeconds = args.optDouble(1, 0.0)
        busy = true  // claim the single slot before the async permission prompt
        try {
            ensureCamera(ctx) { state ->
                if (state["status"] != "granted") { busy = false; reply(null, "camera permission denied"); return@ensureCamera }
                try { capture(ctx, video, maxSeconds, reply) }
                catch (e: Throwable) { busy = false; reply(null, e.message ?: e.toString()) }
            }
        } catch (e: Throwable) {
            busy = false
            reply(null, e.message ?: e.toString())
        }
    }

    private fun capture(ctx: PxModuleContext, video: Boolean, maxSeconds: Double, reply: PxReply) {
        val a = ctx.activity
        // Only one capture is ever in flight, so anything already here was left by a cancel or a killed process.
        val dir = PxCameraFileProvider.dir(a).apply { deleteRecursively(); mkdirs() }
        val target = File(dir, UUID.randomUUID().toString() + if (video) ".mp4" else ".jpg").apply { createNewFile() }
        val uri = PxCameraFileProvider.uriFor(a, target)
        val intent = Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            if (video && maxSeconds > 0) putExtra(MediaStore.EXTRA_DURATION_LIMIT, kotlin.math.ceil(maxSeconds).toInt())
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ctx.startForResult(intent) { code, data ->
                val cr = a.contentResolver
                // Some camera apps ignore EXTRA_OUTPUT for video and return their own content Uri instead.
                val returned = data?.data?.takeIf { it != uri && target.length() == 0L }
                if (code != Activity.RESULT_OK || (target.length() == 0L && returned == null)) {
                    target.delete()
                    busy = false
                    reply(mapOf("assets" to emptyList<Any>()), null)
                    return@startForResult
                }
                val outDir = File(a.cacheDir, "pyxpo-picked").apply { mkdirs() }
                executor.execute {
                    var value: Any? = null
                    var error: String? = null
                    try {
                        val asset = if (returned != null) copy(cr, returned, outDir) else moveCapture(target, outDir, video)
                        value = mapOf("assets" to listOf(asset))
                    } catch (e: Throwable) {
                        error = e.message ?: e.toString()
                    } finally {
                        target.delete()
                        busy = false  // free the slot before Python sees the result
                    }
                    reply(value, error)
                }
            }
        } catch (e: ActivityNotFoundException) {
            target.delete()
            throw IllegalStateException("no camera app is available")
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private fun moveCapture(target: File, outDir: File, video: Boolean): Map<String, Any?> {
        val out = File(outDir, target.name)
        if (!target.renameTo(out)) { target.copyTo(out, overwrite = true); target.delete() }
        if (video) return videoAsset(out, "video/mp4", out.name)
        return imageAsset(out, "image/jpeg", out.name)
    }

    private fun copy(cr: android.content.ContentResolver, uri: Uri, dir: File): Map<String, Any?> {
        val mime = cr.getType(uri) ?: "image/jpeg"
        val video = mime.startsWith("video/")
        var name: String? = null
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
        val ext = (name?.substringAfterLast('.', "") ?: "").ifEmpty { mime.substringAfter('/').replace("jpeg", "jpg") }
        val out = File(dir, UUID.randomUUID().toString() + "." + ext.lowercase())
        (cr.openInputStream(uri) ?: throw java.io.IOException("cannot open picked file")).use { i -> out.outputStream().use { i.copyTo(it) } }
        if (video) return videoAsset(out, mime, name)
        return imageAsset(out, mime, name)
    }

    private fun imageAsset(out: File, mime: String, name: String?): Map<String, Any?> {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(out.path, o)
        val ori = try {
            android.media.ExifInterface(out.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        } catch (e: Exception) { 1 }
        val swap = ori in 5..8
        return mapOf("uri" to out.absolutePath, "type" to "image", "width" to if (swap) o.outHeight else o.outWidth,
            "height" to if (swap) o.outWidth else o.outHeight, "fileSize" to out.length(),
            "mimeType" to mime, "fileName" to (name ?: out.name))
    }

    private fun videoAsset(out: File, mime: String, name: String?): Map<String, Any?> {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(out.path)
            fun num(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            val rot = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val w = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val swap = rot == 90 || rot == 270
            return mapOf("uri" to out.absolutePath, "type" to "video", "width" to if (swap) h else w,
                "height" to if (swap) w else h, "fileSize" to out.length(), "mimeType" to mime,
                "fileName" to (name ?: out.name), "duration" to num(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION))
        } finally {
            r.release()
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pyxpo-imagepicker") }
    }
}
