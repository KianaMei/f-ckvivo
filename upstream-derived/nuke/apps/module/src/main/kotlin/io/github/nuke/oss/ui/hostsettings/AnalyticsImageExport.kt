package io.github.nuke.oss.ui.hostsettings

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

// Exports the local analysis as one PNG in Downloads/Nuke: a title, the summary lines and a row per day. The image is
// drawn here rather than through a document library, so the module gains no dependency for it.
internal object AnalyticsImageExport {
    // Returns where the file went, or null when it could not be written. Blocking; callers run it off the main thread.
    fun export(context: Context, title: String, lines: List<String>, name: String): String? = try {
        val bitmap = render(title, lines)
        val file = File.createTempFile("nuke-report-", ".png", context.cacheDir)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            write(context, file, name)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun render(title: String, lines: List<String>): Bitmap {
        val width = WIDTH
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
        }
        val height = (PADDING * 2 + 64 + lines.size * LINE_HEIGHT).coerceAtLeast(PADDING * 2 + 64)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24, 24, 27))
        canvas.drawText(title, PADDING.toFloat(), (PADDING + 48).toFloat(), titlePaint)
        var y = PADDING + 64 + 32
        for (line in lines) {
            canvas.drawText(line, PADDING.toFloat(), y.toFloat(), bodyPaint)
            y += LINE_HEIGHT
        }
        return bitmap
    }

    private fun write(context: Context, file: File, name: String): String? {
        val resolver = context.contentResolver
        val fileName = if (name.endsWith(".png")) name else "$name.png"
        if (Build.VERSION.SDK_INT < 29) {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nuke")
            if (!directory.isDirectory && !directory.mkdirs()) return null
            val target = File(directory, fileName)
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            return "${Environment.DIRECTORY_DOWNLOADS}/Nuke/$fileName"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Nuke")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { target -> file.inputStream().use { it.copyTo(target) } }
                ?: return null
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return "${Environment.DIRECTORY_DOWNLOADS}/Nuke/$fileName"
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private const val WIDTH = 1080
    private const val PADDING = 56
    private const val LINE_HEIGHT = 52
}
