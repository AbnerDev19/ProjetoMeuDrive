package com.meudrive

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Receives Android shares and starts bounded foreground work owned by TransferService. */
class ShareActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var targets: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var action: android.widget.Button
    private var discovery: Discovery? = null
    private var uris: List<Uri> = emptyList()
    private var sharedText: String? = null
    private var jobId: String? = null
    private val handler=Handler(Looper.getMainLooper())
    private val poll=object:Runnable{override fun run(){showProgress();handler.postDelayed(this,500)}}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(Ui.dp(context,16),Ui.dp(context,24),Ui.dp(context,16),Ui.dp(context,24))}
        val card=Ui.card(this)
        title=Ui.text(this,"Enviar para…",22f,Ui.TEXT,true)
        info=Ui.text(this,"",14f,Ui.MUTED)
        targets=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;visibility=View.GONE}
        action=Ui.primary(this,"Fechar"){finish()}.apply{visibility=View.GONE}
        card.addView(title);card.addView(info);card.addView(targets,Ui.lp(this,12,-1));card.addView(progress,Ui.lp(this,12,-1));card.addView(action,Ui.lp(this,12,-1));root.addView(card)
        root.addView(Ui.text(this,"Envios iniciados aqui continuam com a tela apagada. A notificação permite acompanhar e cancelar. Se o Android encerrar o processo ou revogar o acesso ao arquivo, selecione os pendentes novamente para retomar.",12f,Ui.MUTED))
        setContentView(ScrollView(this).apply{setBackgroundColor(Ui.SIDE);addView(root)})
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 92)
        jobId=intent.getStringExtra(TransferService.EXTRA_JOB)?:state?.getString(TransferService.EXTRA_JOB)
        if(jobId!=null){showProgress();return}
        uris=extractUris(intent);sharedText=if(uris.isEmpty())intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()else null
        if(uris.isEmpty()&&sharedText.isNullOrBlank()){title.text="Nada para enviar";info.text="Escolha arquivos ou compartilhe pelo Android.";action.visibility=View.VISIBLE;return}
        info.text=if(uris.isEmpty())"Texto ou link: ${sharedText.orEmpty().take(100)}" else "${uris.size} arquivo(s) para enviar"
        discovery=Discovery(this).also{it.onChange={renderTargets()};it.start()};renderTargets()
    }
    private fun renderTargets(){
        if(jobId!=null)return
        targets.removeAllViews()
        targets.addView(Ui.primary(this,"Guardar neste celular"){begin(null)},Ui.lp(this,8,-1))
        val prefs=getSharedPreferences("cfg",0)
        val saved=listOf("lan_url" to "Servidor no Wi-Fi","remote_url" to "Fora de casa","client_url" to "Último servidor").mapNotNull{(key,label)->prefs.getString(key,null)?.let{raw->runCatching{label to ServerAddress.parse(raw)}.getOrNull()}}
        val peers=discovery?.peers().orEmpty().mapNotNull{p->runCatching{p.name to ServerAddress.parse("http://${p.host}:${p.port}")}.getOrNull()}
        (saved+peers).distinctBy{it.second.origin}.forEach{(name,address)->
            targets.addView(Ui.secondary(this,"$name\n${address.origin}"){
                AlertDialog.Builder(this).setTitle("Confirmar destino").setMessage("Enviar para ${address.origin}? A chave da casa será enviada apenas a esse endereço.")
                    .setPositiveButton("Enviar"){_,_->begin(address.origin)}.setNegativeButton("Cancelar",null).show()
            },Ui.lp(this,8,-1))
        }
        if(saved.isEmpty()&&peers.isEmpty())targets.addView(Ui.text(this,"Procurando servidores no mesmo Wi-Fi… Para acesso externo, salve o endereço em Fora de casa.",13f,Ui.MUTED))
    }
    private fun begin(origin:String?){
        try{jobId=TransferService.start(this,uris,origin,text=sharedText);targets.visibility=View.GONE;showProgress()}
        catch(e:Exception){Toast.makeText(this,e.message?:"Não foi possível iniciar envio",Toast.LENGTH_LONG).show()}
    }
    private fun showProgress(){
        val id=jobId?:return
        val job=TransferService.job(id)
        targets.visibility=View.GONE;action.visibility=View.VISIBLE
        if(job==null){title.text="Envio não está mais ativo";info.text="O processo foi encerrado. Selecione os arquivos pendentes novamente; o servidor conserva o progresso por até 24 horas.";action.text="Fechar";action.setOnClickListener{finish()};return}
        val active=job.state in listOf("queued","running")
        title.text=when(job.state){"queued"->"Na fila de envio";"running"->"Enviando em segundo plano";"done"->"Envio concluído";"cancelled"->"Envio cancelado";else->"Envio com pendências"}
        info.text="${job.completed} de ${job.total} arquivo(s) concluído(s)\n${job.filename}\n"+(if(job.size>0)"${job.sent/1048576} / ${job.size/1048576} MB\n" else "")+job.error
        progress.visibility=View.VISIBLE;progress.isIndeterminate=active&&job.size<=0;progress.progress=if(job.size>0)(job.sent*100/job.size).toInt()else if(active)0 else 100
        action.text=if(active)"Cancelar envio" else "Fechar"
        action.setOnClickListener{if(active){TransferService.cancel(this,id);showProgress()}else finish()}
    }
    override fun onResume(){super.onResume();handler.post(poll)}
    override fun onPause(){handler.removeCallbacks(poll);super.onPause()}
    override fun onDestroy(){discovery?.stop();handler.removeCallbacks(poll);super.onDestroy()}
    override fun onSaveInstanceState(out:Bundle){out.putString(TransferService.EXTRA_JOB,jobId);super.onSaveInstanceState(out)}
    @Suppress("DEPRECATION") private fun extractUris(i:Intent):List<Uri> = when(i.action){Intent.ACTION_SEND->listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM));Intent.ACTION_SEND_MULTIPLE->i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty();else->emptyList()}
}
