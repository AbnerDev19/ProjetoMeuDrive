package com.meudrive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Semaphore

/** Decodes a sampled raster and re-encodes JPEG. Never returns active image documents. */
class ImagePreview(private val context: Context) {
    data class Result(val bytes: ByteArray, val etag: String)
    private val decoders = Semaphore(2)
    private val cache get() = File(context.cacheDir ?: context.filesDir, "photo-previews").apply { mkdirs() }

    companion object {
        private const val MAX_CACHE = 24L * 1024 * 1024
        private const val MAX_EDGE = 1280
        private val rasterMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/heif", "image/heic", "image/avif")
        fun edge(requested: Int?, full: Boolean): Int = if (full) MAX_EDGE else (requested ?: 320).coerceIn(64, MAX_EDGE)
        fun sample(width: Int, height: Int, edge: Int): Int {
            var sample = 1
            while (maxOf(width, height).toLong() / sample > edge.toLong() * 2) sample *= 2
            return sample
        }
    }

    fun make(file: File, requested: Int?, full: Boolean): Result {
        require(file.isFile) { "Foto não encontrada" }
        val edge = edge(requested, full)
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${file.canonicalPath}|${file.length()}|${file.lastModified()}|$edge|v1".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val etag = "\"preview-$key\""
        val cached = File(cache, "$key.jpg")
        runCatching { if (cached.isFile) cached.readBytes() else null }.getOrNull()?.let {
            cached.setLastModified(System.currentTimeMillis())
            return Result(it, etag)
        }
        check(decoders.tryAcquire()) { "Prévias ocupadas; tente novamente" }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in rasterMimeTypes) {
                "Formato sem prévia. Baixe o original para abrir no aplicativo adequado."
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample(bounds.outWidth, bounds.outHeight, edge)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var bitmap = BitmapFactory.decodeFile(file.path, options)
                ?: throw IllegalArgumentException("Foto inválida ou formato não suportado")
            try {
                val orientation = runCatching {
                    ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                }
                if (!matrix.isIdentity) {
                    val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (oriented !== bitmap) { bitmap.recycle(); bitmap = oriented }
                }
                val ratio = minOf(1.0, edge.toDouble() / maxOf(bitmap.width, bitmap.height))
                if (ratio < 1) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true)
                    if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled }
                }
                if (bitmap.hasAlpha()) {
                    val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
                    Canvas(flattened).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    bitmap.recycle(); bitmap = flattened
                }
                val bytes = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) { "Não foi possível gerar a prévia" }
                    output.toByteArray()
                }
                // Unique temporary names avoid concurrent cache writes racing on rename.
                val temp = File.createTempFile("preview-", ".tmp", cache)
                try { temp.writeBytes(bytes); temp.renameTo(cached) } finally { temp.delete() }
                trim()
                return Result(bytes, etag)
            } finally { bitmap.recycle() }
        } finally { decoders.release() }
    }

    @Synchronized private fun trim() {
        val files = cache.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= MAX_CACHE) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }
}
