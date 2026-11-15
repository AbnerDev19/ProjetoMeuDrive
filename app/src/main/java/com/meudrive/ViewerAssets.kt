package com.meudrive

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.util.zip.ZipInputStream

/** Only bundled static resources; document content stays behind authenticated /api/open. */
object ViewerAssets {
    fun serve(ctx: Context, path: String): NanoHTTPD.Response? {
        if (path == "/pdf-viewer.html") return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK,
            "text/html; charset=utf-8", ctx.assets.open("pdf-viewer.html"))
        if (!path.startsWith("/pdfjs/")) return null
        val name = path.removePrefix("/pdfjs/")
        if (name.isBlank() || name.split('/').any { it == ".." || it == "." } || name.contains('\\')) return null
        val zip = ZipInputStream(ctx.assets.open("pdfjs.zip"))
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name && !entry.isDirectory) return NanoHTTPD.newChunkedResponse(
                    NanoHTTPD.Response.Status.OK, mime(name), zip)
            }
        } catch (e: Exception) { zip.close(); throw e }
        zip.close()
        return null
    }
    private fun mime(name: String) = when (name.substringAfterLast('.')) {
        "mjs", "js" -> "text/javascript; charset=utf-8"
        "wasm" -> "application/wasm"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
