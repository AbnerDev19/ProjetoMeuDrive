package com.meudrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** User-started uploads continue independently of the activity, with bounded foreground work. */
class TransferService : Service() {
    data class Job(val id: String, val uris: List<Uri>, val origin: String?, val destination: String,
                   val text: String?, val cookie: String?, val key: String?) {
        @Volatile var state = "queued"
        @Volatile var completed = 0
        @Volatile var failed = 0
        @Volatile var sent = 0L
        @Volatile var size = 0L
        @Volatile var filename = ""
        @Volatile var error = ""
        @Volatile var cancelled = false
        @Volatile var finishedAt = 0L
        val total get() = if (uris.isEmpty()) 1 else uris.size
        fun snapshot() = JSONObject().put("id", id).put("state", state).put("completed", completed)
            .put("failed", failed).put("total", total).put("sent", sent).put("size", size)
            .put("name", filename).put("error", error).put("destination", destination)
    }
    companion object {
        const val EXTRA_JOB = "transfer_job"
        private const val CANCEL = "com.meudrive.CANCEL_UPLOAD"
        private const val CHANNEL = "uploads"
        private const val NOTIFICATION = 20
        private val jobs = ConcurrentHashMap<String, Job>()
        fun job(id: String?) = id?.let { jobs[it] }
        fun start(context: Context, uris: List<Uri>, origin: String? = null, destination: String = "Transferencias",
                  text: String? = null, cookie: String? = null): String {
            require(uris.size <= 500 && (uris.isNotEmpty() || !text.isNullOrBlank())) { "Escolha de 1 a 500 arquivos" }
            val normalized = origin?.let { ServerAddress.parse(it).origin }
            val key = context.getSharedPreferences("cfg", 0).getString("pwhash", null)
            require(normalized == null || !cookie.isNullOrBlank() || key != null) { "Entre no servidor ou defina a senha da casa" }
            jobs.entries.removeAll { it.value.finishedAt > 0 && System.currentTimeMillis()-it.value.finishedAt > 3600000 }
            require(jobs.values.count { it.state == "running" || it.state == "queued" } < 4) { "Aguarde a fila atual terminar" }
            val id = UUID.randomUUID().toString()
            val job = Job(id, uris.toList(), normalized, destination, text, cookie?.takeIf { it.isNotBlank() }, if (cookie.isNullOrBlank()) key else null)
            jobs[id] = job
            val intent = Intent(context, TransferService::class.java).putExtra(EXTRA_JOB, id)
            if (uris.isNotEmpty()) {
                intent.clipData = ClipData.newRawUri("Arquivos selecionados", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            }
            try { context.startForegroundService(intent) }
            catch (e: Exception) { jobs.remove(id); throw e }
            return id
        }
        fun cancel(context: Context, id: String) {
            jobs[id]?.cancelled = true
            context.startService(Intent(context, TransferService::class.java).setAction(CANCEL).putExtra(EXTRA_JOB,id))
        }
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger()
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var current: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val job = job(intent?.getStringExtra(EXTRA_JOB))
        if (intent?.action == CANCEL) { job?.cancelled = true; if(pending.get()==0)stopSelf(); return START_NOT_STICKY }
        if (job == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"Envios do MeuDrive",NotificationManager.IMPORTANCE_LOW))
        val initial = notification(job)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION,initial,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION,initial)
        pending.incrementAndGet()
        executor.execute {
            try { current = job; acquire(); run(job) }
            catch(e: Exception) { job.state="failed";job.error=e.message?:"Não foi possível iniciar envio em segundo plano";job.failed=maxOf(1,job.failed) }
            finally {
                release(); current = null
                job.finishedAt = System.currentTimeMillis()
                pending.decrementAndGet()
                android.os.Handler(mainLooper).post {
                    if (pending.get() == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (nm.areNotificationsEnabled()) nm.notify(job.id.hashCode(),notification(job,true))
                        stopSelfResult(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    @Synchronized private fun acquire() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"meudrive:upload").apply { setReferenceCounted(false); acquire() }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"meudrive:upload-wifi").apply { setReferenceCounted(false); acquire() }
    }
    @Synchronized private fun release() { wake?.let { if(it.isHeld)it.release() }; wake=null;wifi?.let{if(it.isHeld)it.release()};wifi=null }
    private fun check(job: Job) { if(job.cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Envio cancelado") }
    private fun run(job: Job) {
        job.state="running"
        try {
            check(job)
            if(job.uris.isEmpty()) {
                if(job.origin==null) Inbox.add(this,"text",job.text.orEmpty(),"Este celular") else sendText(job)
                job.completed=1
            } else job.uris.forEach { uri ->
                check(job)
                try {
                    val (name,size)=metadata(uri);job.filename=name;job.size=size;job.sent=0
                    update(job)
                    if(job.origin==null) copyLocal(job,uri,name,size)
                    else TransferClient(this,job.cookie).send(job.origin,job.key,uri,name,size,job.destination,{job.cancelled}) { sent,total ->
                        check(job);job.sent=sent;job.size=total;update(job)
                    }
                    job.completed++
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { job.failed++;job.error=e.message?:"Falha ao enviar arquivo" }
            }
            job.state=if(job.failed>0)"failed" else "done"
        } catch(e: CancellationException) { job.state="cancelled";job.error="Envio cancelado; arquivos concluídos foram mantidos. Selecione os pendentes para retomar." }
        catch(e: Exception) { job.state="failed";job.error=e.message?:"Falha no envio";job.failed=maxOf(1,job.failed) }
        update(job)
    }
    private var lastUpdate=0L
    private fun update(job: Job) {
        val now=System.currentTimeMillis()
        if(job.state=="running"&&now-lastUpdate<500)return
        lastUpdate=now
        val nm=getSystemService(NotificationManager::class.java)
        if(nm.areNotificationsEnabled())nm.notify(NOTIFICATION,notification(job))
    }
    private fun notification(job: Job,finished: Boolean=false): Notification {
        val open=PendingIntent.getActivity(this,job.id.hashCode(),Intent(this,ShareActivity::class.java).putExtra(EXTRA_JOB,job.id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel=PendingIntent.getService(this,job.id.hashCode(),Intent(this,TransferService::class.java).setAction(CANCEL).putExtra(EXTRA_JOB,job.id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title=when(job.state){"done"->"Envio concluído";"failed"->"Envio com pendências";"cancelled"->"Envio cancelado";else->"MeuDrive enviando arquivos"}
        val builder=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title).setContentText("${job.completed}/${job.total} • ${job.filename.ifEmpty { "Preparando envio" }}")
            .setContentIntent(open).setOngoing(!finished&&job.state in listOf("running","queued")).setAutoCancel(finished)
        if(!finished&&job.state in listOf("running","queued")) builder.setProgress(100,if(job.size>0)(job.sent*100/job.size).toInt() else 0,job.size<=0).addAction(Notification.Action.Builder(null,"Cancelar",cancel).build())
        return builder.build()
    }
    private fun metadata(uri: Uri): Pair<String,Long> {
        var name=uri.lastPathSegment?:"arquivo";var size=-1L
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
            if(c.moveToFirst()){name=c.getString(0)?:name;size=if(c.isNull(1))-1 else c.getLong(1)}
        }
        val safe=File(name).name
        require(TransferStore.validName(safe)){"Nome de arquivo inválido"}
        return safe to size
    }
    private fun copyLocal(job: Job,uri: Uri,name:String,size:Long) {
        val root=Cfg.rootDir(this).canonicalFile
        val directory=File(root,job.destination).canonicalFile
        require(directory==root||directory.path.startsWith(root.path+File.separator)){"Destino inválido"}
        directory.mkdirs()
        val staging=File(getExternalFilesDir(null)?:filesDir,".local-transfers").apply{mkdirs()}
        check(staging.usableSpace-TransferStore.RESERVE>=maxOf(0,size)){"Espaço insuficiente; mantenha 128 MiB livres"}
        val part=File.createTempFile("upload-",".part",staging)
        try {
            contentResolver.openInputStream(uri)!!.use { source ->part.outputStream().use { out ->
                val buffer=ByteArray(256*1024);var total=0L
                while(true){check(job);check(staging.usableSpace>TransferStore.RESERVE){"Espaço insuficiente"};val n=source.read(buffer);if(n<0)break;out.write(buffer,0,n);total+=n;job.sent=total;update(job)}
            } }
            check(job)
            var destination=File(directory,name);var i=1;val original=File(name)
            while(destination.exists())destination=File(directory,"${original.nameWithoutExtension} (${i++})${original.extension.let{if(it.isEmpty())"" else ".$it"}}")
            check(part.renameTo(destination)){"Não foi possível guardar arquivo"}
            Inbox.add(this,"file",destination.name,"Este celular",destination.relativeTo(root).path)
        }finally{part.delete()}
    }
    private fun sendText(job:Job) {
        val body="text=${URLEncoder.encode(job.text.orEmpty(),"UTF-8")}&from=${URLEncoder.encode(Cfg.deviceName(this),"UTF-8")}".toByteArray()
        val c=URL(job.origin+"/api/send-text").openConnection() as HttpURLConnection
        try{c.requestMethod="POST";c.doOutput=true;c.instanceFollowRedirects=false;c.connectTimeout=5000;c.readTimeout=30000
            job.key?.let{c.setRequestProperty("X-Key",it)};job.cookie?.let{c.setRequestProperty("Cookie",it)}
            c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use{it.write(body)};check(c.responseCode in 200..299){"O servidor recusou o texto (${c.responseCode})"}
        }finally{c.disconnect()}
    }
    override fun onDestroy(){current?.cancelled=true;executor.shutdownNow();release();super.onDestroy()}
}
