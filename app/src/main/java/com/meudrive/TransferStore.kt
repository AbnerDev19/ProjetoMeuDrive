package com.meudrive

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Semaphore

/** Disk-backed resumable uploads. No whole-file buffering or second full-size copy. */
class TransferStore(private val staging: File, private val resolve: (String) -> File?) {
    companion object {
        const val CHUNK = 8 * 1024 * 1024
        const val RESERVE = 128L * 1024 * 1024
        const val TTL = 24L * 60 * 60 * 1000
        fun validName(name: String) = name.isNotBlank() && name != "." && name != ".." &&
            name.length <= 180 && name.none { it == '/' || it == '\\' || it.code < 32 }
    }
    data class State(val id: String, val offset: Long, val size: Long, val name: String, val done: Boolean)
    private val slots = Semaphore(2)

    init { staging.mkdirs(); cleanup() }

    @Synchronized fun start(path: String, name: String, size: Long, from: String = "Rede local"): State {
        require(validName(name)) { "Nome de arquivo inválido" }
        require(size >= 0) { "Tamanho inválido" }
        val dir = resolve(path) ?: error("Pasta inválida")
        require(dir.isDirectory) { "A pasta não existe" }
        checkSpace(size)
        val id = UUID.randomUUID().toString()
        val p = Properties().apply {
            setProperty("path", path); setProperty("name", name); setProperty("size", "$size")
            setProperty("destination", dir.canonicalPath); setProperty("from", from.take(120))
        }
        data(id).createNewFile()
        save(id, p)
        return if (size == 0L) finish(id, p) else state(id)
    }

    @Synchronized fun state(id: String): State {
        val p = read(id)
        return State(id, if (p.getProperty("done") == "true") p.getProperty("size").toLong() else data(id).length(),
            p.getProperty("size").toLong(), p.getProperty("name"), p.getProperty("done") == "true")
    }

    fun append(id: String, offset: Long, length: Long, input: InputStream): State {
        require(length in 1..CHUNK.toLong()) { "Bloco deve ter até 8 MiB" }
        check(slots.tryAcquire()) { "Servidor ocupado; tente novamente" }
        try {
            // Serializes commit/status with append, avoiding duplicate retries corrupting a file.
            synchronized(this) {
                val p = read(id)
                val current = state(id)
                if (current.done) return current
                require(offset == current.offset) { "Offset divergente; consulte o progresso" }
                require(length <= current.size - offset) { "Bloco excede o tamanho declarado" }
                checkSpace(length)
                RandomAccessFile(data(id), "rw").use { out ->
                    out.seek(offset)
                    try {
                        val buffer = ByteArray(256 * 1024)
                        var remaining = length
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(n > 0) { "Conexão interrompida; reenvie o bloco" }
                            out.write(buffer, 0, n); remaining -= n
                        }
                        out.fd.sync()
                    } catch (e: Exception) {
                        out.setLength(offset)
                        throw e
                    }
                }
                metadata(id).setLastModified(System.currentTimeMillis())
                return if (offset + length == current.size) finish(id, p) else state(id)
            }
        } finally { slots.release() }
    }

    private fun finish(id: String, p: Properties): State {
        val dir = resolve(p.getProperty("path")) ?: error("Pasta indisponível")
        check(dir.isDirectory && dir.canonicalPath == p.getProperty("destination")) { "Pasta do servidor mudou" }
        var name = p.getProperty("name")
        val original = File(name)
        var n = 1
        var dest = File(dir, name)
        while (dest.exists()) {
            name = original.nameWithoutExtension + " (${n++})" + original.extension.let { if (it.isEmpty()) "" else ".$it" }
            dest = File(dir, name)
        }
        check(data(id).renameTo(dest)) { "Não foi possível salvar o arquivo" }
        p.setProperty("name", name); p.setProperty("done", "true")
        save(id, p)
        return state(id)
    }

    data class Receipt(val path: String, val name: String, val from: String)
    @Synchronized fun takeReceipt(id: String): Receipt? {
        val p = read(id)
        if (p.getProperty("done") != "true" || p.getProperty("receipt") == "true") return null
        p.setProperty("receipt", "true"); save(id, p)
        val path = p.getProperty("path").let { if (it.isEmpty()) p.getProperty("name") else "$it/${p.getProperty("name")}" }
        return Receipt(path, p.getProperty("name"), p.getProperty("from", "Rede local"))
    }

    private fun checkSpace(bytes: Long) {
        check(staging.usableSpace - RESERVE >= bytes) { "Espaço insuficiente; mantenha 128 MiB livres para o Android" }
    }
    private fun validId(id: String) { require(Regex("[a-f0-9-]{36}").matches(id)) { "Transferência inválida" } }
    private fun data(id: String): File { validId(id); return File(staging, "$id.part") }
    private fun metadata(id: String): File { validId(id); return File(staging, "$id.properties") }
    private fun read(id: String): Properties {
        val f = metadata(id)
        require(f.isFile && System.currentTimeMillis() - f.lastModified() < TTL) { "Transferência expirada; inicie novamente" }
        return Properties().apply { f.inputStream().use { load(it) } }
    }
    private fun save(id: String, p: Properties) {
        val temp = File(staging, "$id.meta.tmp")
        temp.outputStream().use { p.store(it, null) }
        check(temp.renameTo(metadata(id))) { "Falha ao salvar progresso" }
    }
    @Synchronized fun cleanup() {
        staging.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > TTL }?.forEach { it.delete() }
    }
}

/** A single HTTP byte range, including open-ended and suffix requests. */
data class ByteRange(val start: Long, val end: Long) {
    companion object {
        fun parse(header: String, size: Long): ByteRange? {
            if (size <= 0 || !header.startsWith("bytes=") || header.contains(',')) return null
            val parts = header.removePrefix("bytes=").split('-', limit = 2)
            if (parts.size != 2) return null
            val start: Long
            val end: Long
            if (parts[0].isEmpty()) {
                val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
                start = maxOf(0, size - suffix); end = size - 1
            } else {
                start = parts[0].toLongOrNull() ?: return null
                end = if (parts[1].isEmpty()) size - 1 else minOf(parts[1].toLongOrNull() ?: return null, size - 1)
            }
            return if (start >= 0 && start < size && end >= start) ByteRange(start, end) else null
        }
    }
}
