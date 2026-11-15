package com.meudrive

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounds connection threads and queued sockets on a dedicated, modest Android phone. */
class BoundedRunner : NanoHTTPD.AsyncRunner {
    private val clients = ConcurrentHashMap.newKeySet<NanoHTTPD.ClientHandler>()
    private val pool = ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16)) { task ->
        Thread(task, "meudrive-http").apply { isDaemon = true }
    }
    override fun exec(client: NanoHTTPD.ClientHandler) {
        clients.add(client)
        try { pool.execute(client) } catch (_: RejectedExecutionException) { client.close(); clients.remove(client) }
    }
    override fun closed(client: NanoHTTPD.ClientHandler) { clients.remove(client) }
    override fun closeAll() { clients.toList().forEach { it.close() }; clients.clear(); pool.shutdownNow() }
}
