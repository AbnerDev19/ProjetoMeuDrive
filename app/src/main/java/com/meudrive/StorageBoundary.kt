package com.meudrive
import java.io.File

/** Checks paths relative to the shared root, including Android/media but not app-private areas. */
object StorageBoundary {
    fun permitted(relative: String): Boolean {
        if (relative.startsWith('/') || relative.contains('\\')) return false
        val parts=relative.split('/')
        if(parts.any { it == ".." || it in setOf(".transfers", ".local-transfers", ".meudrive-transfers") }) return false
        return !(parts.firstOrNull() == "Android" && parts.getOrNull(1) in setOf("data", "obb"))
    }
    fun resolve(root: File, relative: String): File? {
        if(!permitted(relative)) return null
        val base=root.canonicalFile
        val file=File(base,relative).canonicalFile
        if(file!=base&&!file.path.startsWith(base.path+File.separator)) return null
        return file.takeIf { permitted(it.relativeTo(base).invariantSeparatorsPath) }
    }
}
