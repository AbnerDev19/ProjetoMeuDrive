package com.meudrive

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

data class Peer(val name: String, val host: String, val port: Int)

/** Anuncia este aparelho na rede (mDNS/NSD) e descobre os outros Meu Drive. */
class Discovery(ctx: Context) {

    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var reg: NsdManager.RegistrationListener? = null
    private var disc: NsdManager.DiscoveryListener? = null
    private var ownName: String? = null
    private val found = ConcurrentHashMap<String, Peer>()
    private val queue = LinkedList<NsdServiceInfo>()
    private var resolving = false

    var onChange: (() -> Unit)? = null

    fun peers(): List<Peer> = found.values.sortedBy { it.name.lowercase() }

    fun register(name: String, port: Int) {
        if (reg != null) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Cfg.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { ownName = i.serviceName }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { reg = null }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        reg = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun start() {
        if (disc != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { disc = null }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == ownName) return
                synchronized(queue) { queue.add(i) }
                pump()
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                found.remove(i.serviceName)
                changed()
            }
        }
        disc = l
        nsd.discoverServices(Cfg.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    // o Android só resolve um serviço por vez, então fazemos uma fila
    private fun pump() {
        var next: NsdServiceInfo? = null
        synchronized(queue) {
            if (resolving || queue.isEmpty()) return
            resolving = true
            next = queue.poll()
        }
        val target = next ?: run { finishResolve(); return }
        try {
            nsd.resolveService(target, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) { finishResolve() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val addr = i.host
                    if (addr is Inet4Address) {
                        val h = addr.hostAddress
                        // ignora o próprio aparelho
                        if (h != null && !Cfg.ips().contains(h)) {
                            found[i.serviceName] = Peer(i.serviceName, h, i.port)
                            changed()
                        }
                    }
                    finishResolve()
                }
            })
        } catch (e: Exception) {
            finishResolve()
        }
    }

    private fun finishResolve() {
        synchronized(queue) { resolving = false }
        pump()
    }

    private fun changed() { main.post { onChange?.invoke() } }

    fun stop() {
        reg?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        reg = null
        disc?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        disc = null
        found.clear()
        synchronized(queue) { queue.clear(); resolving = false }
    }
}
