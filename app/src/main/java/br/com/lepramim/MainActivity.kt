package br.com.lepramim

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var page = ""
    private var offset = 0
    private var base = 0
    private var serial = 0
    private var speaking = false
    private var busy = false
    private var speed = 1f
    private var photo: Uri? = null
    private lateinit var reader: TextView
    private lateinit var status: TextView
    private lateinit var play: Button
    private lateinit var camera: Button
    private lateinit var voices: Spinner
    private var available = emptyList<Voice>()
    private val capture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photo?.let { scan(it) } else { busy = false; camera.isEnabled = true; status.text = "Foto cancelada." }
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scan(uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(30,60,52)
        window.navigationBarColor = Color.rgb(30,60,52)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(24))
            setBackgroundColor(Color.rgb(250,247,238))
        }
        setContentView(root)
        root.addView(TextView(this).apply { text = "Lê pra Mim"; textSize = 30f; setTextColor(Color.rgb(30,60,52)); gravity = Gravity.CENTER })
        status = TextView(this).apply { text = "Fotografe uma página para ouvir."; textSize = 17f; gravity = Gravity.CENTER }
        root.addView(status)
        camera = button("Fotografar página") { takePhoto() }
        root.addView(camera)
        root.addView(button("Escolher foto da galeria") { if (!busy) { pause(); gallery.launch("image/*") } })
        reader = TextView(this).apply { textSize = 22f; setTextColor(Color.rgb(35,35,35)); setLineSpacing(dp(7).toFloat(),1.15f); text = "O texto aparecerá aqui."; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(reader) }
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        play = button("Ouvir") { if (speaking) pause() else speak() }
        root.addView(play)
        val row = LinearLayout(this)
        row.addView(button("Voltar trecho") { pause(); offset = (offset-150).coerceAtLeast(0); if(offset>0) { val space=page.indexOf(' ',offset); if(space>=0) offset=space+1 }; speak() }, LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button("Recomeçar") { pause(); offset=0; speak() }, LinearLayout.LayoutParams(0,-2,1f))
        root.addView(row)
        val rate = TextView(this).apply { text = "Velocidade: 1.0x"; textSize=16f }
        root.addView(rate)
        root.addView(SeekBar(this).apply { max=15; progress=5; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s:SeekBar?, p:Int, user:Boolean) { speed=0.5f+p/10f; rate.text="Velocidade: ${speed}x" }
            override fun onStartTrackingTouch(s:SeekBar?) {}
            override fun onStopTrackingTouch(s:SeekBar?) { val was=speaking; pause(); if(was) speak() }
        }) })
        root.addView(TextView(this).apply { text="Voz (português)"; textSize=16f })
        voices = Spinner(this)
        root.addView(voices)
        root.addView(button("Instalar / configurar vozes") { try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch(e:Exception) { status.text="Configure a síntese de voz nos ajustes do Android." } })
        page=savedInstanceState?.getString("page") ?: ""
        offset=(savedInstanceState?.getInt("offset") ?: 0).coerceIn(0,page.length)
        photo=savedInstanceState?.getString("photo")?.let(Uri::parse)
        if(page.isNotBlank()) reader.text=page
        tts=TextToSpeech(this) { code -> runOnUiThread { if(code==TextToSpeech.SUCCESS) setupVoice() else status.text="Síntese de voz indisponível." } }
    }
    private fun dp(n:Int) = (n*resources.displayMetrics.density).toInt()
    private fun button(label:String, action:()->Unit) = Button(this).apply { text=label; textSize=16f; isAllCaps=false; minHeight=dp(48); setOnClickListener { action() } }
    private fun takePhoto() {
        if(busy) return
        pause()
        try {
            val dir=File(cacheDir,"photos").apply { mkdirs() }
            val file=File.createTempFile("page",".jpg",dir)
            photo=FileProvider.getUriForFile(this,"$packageName.files",file)
            busy=true; camera.isEnabled=false; capture.launch(photo!!)
        } catch(e:Exception) { busy=false; camera.isEnabled=true; status.text="Não foi possível abrir a câmera." }
    }
    private fun scan(uri:Uri) {
        pause(); busy=true; camera.isEnabled=false; status.text="Reconhecendo texto..."
        try {
            recognizer.process(InputImage.fromFilePath(this,uri))
                .addOnSuccessListener(this) { result ->
                    busy=false; camera.isEnabled=true
                    if(result.text.isBlank()) { status.text="Nenhum texto encontrado. Tente outra foto."; return@addOnSuccessListener }
                    page=result.text.trim(); offset=0; reader.text=page
                    status.text="Página reconhecida."
                    speak()
                }.addOnFailureListener(this) { busy=false; camera.isEnabled=true; status.text="Falha no reconhecimento. Tente outra foto." }
        } catch(e:Exception) { busy=false; camera.isEnabled=true; status.text="Não foi possível abrir a foto." }
    }
    private fun setupVoice() {
        val engine=tts ?: return
        val locale=Locale("pt","BR")
        val language=engine.setLanguage(locale)
        available=engine.voices.orEmpty().filter { it.locale.language=="pt" && !it.features.orEmpty().contains("notInstalled") }.sortedWith(compareBy<Voice> { it.locale.country!="BR" }.thenBy { it.isNetworkConnectionRequired }.thenByDescending { it.quality })
        ready=language>=0 || available.isNotEmpty()
        voices.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,available.map { "${it.locale.toLanguageTag()} • ${if(it.isNetworkConnectionRequired) "Online" else "Local"} • ${it.name}" })
        voices.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?, view:android.view.View?, position:Int,id:Long) {
                val was=speaking; if(was) pause(); available.getOrNull(position)?.let { engine.voice=it }; if(was) speak()
            }
        }
        engine.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
            override fun onStart(id:String?) {}
            override fun onDone(id:String?) { runOnUiThread { if(id==serial.toString() && speaking) { offset=base+chunkLength; if(offset<page.length) speakChunk() else { speaking=false; offset=0; play.text="Ouvir"; status.text="Página concluída."; reader.text=page } } } }
            override fun onError(id:String?) { runOnUiThread { if(id==serial.toString()) { pause(); status.text="Falha na voz. Escolha outra voz." } } }
            override fun onRangeStart(id:String?, start:Int,end:Int,frame:Int) { runOnUiThread {
                if(id==serial.toString() && speaking) {
                    offset=(base+start).coerceIn(0,page.length)
                    val finish=(base+end).coerceIn(offset,page.length)
                    reader.text=SpannableString(page).apply { setSpan(BackgroundColorSpan(Color.YELLOW),offset,finish,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
            } }
        })
        if(!ready) status.text="Instale uma voz em português."
    }
    private var chunkLength=0
    private fun speak() {
        if(busy || page.isBlank()) return
        if(!ready) { status.text="A voz ainda não está disponível."; return }
        speaking=true; play.text="Pausar"; status.text="Lendo página..."
        speakChunk()
    }
    private fun speakChunk() {
        if(offset>=page.length) offset=0
        base=offset
        var end=(base+TextToSpeech.getMaxSpeechInputLength().coerceAtMost(1000)).coerceAtMost(page.length)
        if(end<page.length) { val space=page.lastIndexOf(' ',end); if(space>base) end=space+1 }
        chunkLength=end-base
        serial++
        val engine=tts ?: return
        engine.setSpeechRate(speed)
        if(engine.speak(page.substring(base,end),TextToSpeech.QUEUE_FLUSH,null,serial.toString())==TextToSpeech.ERROR) { pause(); status.text="Não foi possível iniciar a voz." }
    }
    private fun pause() { serial++; speaking=false; tts?.stop(); if(::play.isInitialized) play.text="Ouvir"; if(::status.isInitialized && page.isNotBlank()) status.text="Leitura pausada." }
    override fun onSaveInstanceState(out:Bundle) { out.putString("page",page); out.putInt("offset",offset); out.putString("photo",photo?.toString()); super.onSaveInstanceState(out) }
    override fun onStop() { pause(); super.onStop() }
    override fun onDestroy() { recognizer.close(); tts?.shutdown(); super.onDestroy() }
}
