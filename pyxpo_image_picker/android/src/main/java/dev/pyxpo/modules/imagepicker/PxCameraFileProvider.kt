package dev.pyxpo.modules.imagepicker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands the camera app a writable target for one capture: an existing file in `<cache>/pyxpo-camera`, addressed as
 * content://<applicationId>.pyxpo.imagepicker/<file name>. Not exported, so only an app holding a per-URI grant
 * from the capture intent can open it. Plain ContentProvider, so no AndroidX is needed.
 */
class PxCameraFileProvider : ContentProvider() {
    override fun onCreate() = true

    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        val name = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
        val root = dir(ctx)
        val f = File(root, name).canonicalFile
        if (uri.pathSegments.size != 1 || f.parentFile != root || !f.isFile) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.parseMode(mode))

    override fun getType(uri: Uri): String? = try {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(resolve(uri).extension.lowercase()) ?: "application/octet-stream"
    } catch (e: FileNotFoundException) { null }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val f = resolve(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cur = MatrixCursor(cols)
        cur.addRow(Array<Any?>(cols.size) { i -> when (cols[i]) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        return cur
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("not supported")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("not supported")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("not supported")

    companion object {
        /** Holds only the capture in flight; anything else in it is left over from a cancel or a killed process. */
        fun dir(ctx: Context): File = File(ctx.cacheDir, "pyxpo-camera").canonicalFile

        fun uriFor(ctx: Context, file: File): Uri {
            require(file.canonicalFile.parentFile == dir(ctx)) { "camera files must live in ${dir(ctx)}" }
            return Uri.Builder().scheme("content").authority(ctx.packageName + ".pyxpo.imagepicker").appendPath(file.name).build()
        }
    }
}
