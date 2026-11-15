package com.meudrive

import java.io.File
import java.util.ArrayDeque

/** Cached, bounded recursive catalogue restricted by the same resolver as file access. */
class MediaIndex(private val root: () -> File, private val resolve: (String) -> File?) {
    data class Item(val name: String, val path: String, val kind: String, val size: Long, val mtime: Long)
    data class Page(val items: List<Item>, val total: Int, val nextOffset: Int?, val partial: Boolean, val scannedAt: Long)
    private var cachedRoot = ""
    private var scannedAt = 0L
    private var cached = emptyList<Item>()
    private var partial = false

    companion object {
        private val photos = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        private val videos = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi", "mpeg", "mpg")
        fun kind(file: File): String? = when (file.extension.lowercase()) {
            in photos -> "photo"
            in videos -> "video"
            "pdf" -> "pdf"
            else -> null
        }
        fun mime(file: File): String? = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mpeg", "mpg" -> "video/mpeg"
            "pdf" -> "application/pdf"
            else -> null
        }
    }

    @Synchronized fun page(kind: String, offset: Int, limit: Int, refresh: Boolean = false): Page {
        require(kind in setOf("all", "photo", "video", "pdf")) { "Tipo de mídia inválido" }
        require(offset >= 0 && limit in 1..100) { "Página inválida" }
        val base = root().canonicalFile
        if (refresh || base.path != cachedRoot || System.currentTimeMillis() - scannedAt > 60000) rebuild(base)
        val filtered = if (kind == "all") cached else cached.filter { it.kind == kind }
        val result = filtered.drop(offset).take(limit)
        return Page(result, filtered.size, (offset + result.size).takeIf { it < filtered.size }, partial, scannedAt)
    }

    private fun rebuild(base: File) {
        val out = ArrayList<Item>()
        val pending = ArrayDeque<File>()
        pending.add(base)
        val started = System.currentTimeMillis()
        var examined = 0
        var truncated = false
        while (pending.isNotEmpty()) {
            if (out.size >= 50000 || examined >= 200000 || System.currentTimeMillis() - started > 10000) { truncated = true; break }
            val folder = pending.removeFirst()
            val files = folder.listFiles()
            if (files == null) { truncated = true; continue }
            for (file in files) {
                if (++examined > 200000 || out.size >= 50000 || (examined % 256 == 0 && System.currentTimeMillis() - started > 10000)) { truncated = true; break }
                // Do not follow symlinks (including cycles within the root).
                val relative = file.relativeTo(base).invariantSeparatorsPath
                val permitted = runCatching { resolve(relative) }.getOrNull() ?: continue
                if (permitted.path != file.absoluteFile.path) continue
                if (file.name in setOf(".transfers", ".local-transfers", ".meudrive-transfers")) continue
                if (file.isDirectory) pending.add(file)
                else if (file.isFile) kind(file)?.let { out.add(Item(file.name, relative, it, file.length(), file.lastModified())) }
            }
        }
        cached = out.sortedWith(compareByDescending<Item> { it.mtime }.thenBy { it.path })
        partial = truncated
        cachedRoot = base.path
        scannedAt = System.currentTimeMillis()
    }
}
