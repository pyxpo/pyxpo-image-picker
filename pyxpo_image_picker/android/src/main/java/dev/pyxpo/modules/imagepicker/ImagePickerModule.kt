package dev.pyxpo.modules.imagepicker

import android.app.Activity
import android.content.Intent
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
 * Photo library picker. Uses the system photo picker on Android 13+ (no permission) and ACTION_GET_CONTENT before.
 * Picked files are copied into the app cache with their original bytes, so `uri` is a local path.
 */
class ImagePickerModule : PxModule() {
    override val name = "ImagePicker"
    private var busy = false

    override fun call(ctx: PxModuleContext, method: String, args: JSONArray, reply: PxReply) {
        if (method != "pick") { reply(null, "unknown native call ImagePicker.$method"); return }
        if (busy) { reply(null, "a picker is already open"); return }
        val multiple = args.optBoolean(0, false)
        val limit = args.optInt(1, 0)
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                if (multiple) {
                    val max = MediaStore.getPickImagesMaxLimit()
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, if (limit in 2..max) limit else max)
                }
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
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

    private fun copy(cr: android.content.ContentResolver, uri: Uri, dir: File): Map<String, Any?> {
        val mime = cr.getType(uri) ?: "image/jpeg"
        var name: String? = null
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
        val ext = (name?.substringAfterLast('.', "") ?: "").ifEmpty { mime.substringAfter('/').replace("jpeg", "jpg") }
        val out = File(dir, UUID.randomUUID().toString() + "." + ext.lowercase())
        (cr.openInputStream(uri) ?: throw java.io.IOException("cannot open picked image")).use { i -> out.outputStream().use { i.copyTo(it) } }
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(out.path, o)
        val ori = try {
            android.media.ExifInterface(out.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        } catch (e: Exception) { 1 }
        val swap = ori in 5..8
        return mapOf("uri" to out.absolutePath, "width" to if (swap) o.outHeight else o.outWidth,
            "height" to if (swap) o.outWidth else o.outHeight, "fileSize" to out.length(),
            "mimeType" to mime, "fileName" to (name ?: out.name))
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pyxpo-imagepicker") }
    }
}
