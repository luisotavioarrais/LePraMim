package br.com.lepramim

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.app.AlertDialog
import org.json.JSONArray
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
    private val pages = mutableListOf<String>()
    private lateinit var pageList: TextView
    private var importing = false
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
        if (ok) photo?.let { crop(it) } else { busy = false; camera.isEnabled = true; status.text = "Foto cancelada." }
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) crop(uri)
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
        val cards = LinearLayout(this)
        val galleryButton = button("Escolher foto\nDa galeria") { if (!busy) { pause(); gallery.launch("image/*") } }
        styleCard(galleryButton,0xfffaedca.toInt(),0xff24251f.toInt())
        styleCard(camera,0xff28634e.toInt(),Color.WHITE)
        cards.addView(galleryButton,LinearLayout.LayoutParams(0,dp(115),1f).apply { marginEnd=dp(8) })
        cards.addView(camera,LinearLayout.LayoutParams(0,dp(115),1f))
        root.addView(cards)
        root.addView(button("Colar ou digitar texto") { if(!busy) { val edit=EditText(this); AlertDialog.Builder(this).setTitle("Adicionar texto").setView(edit).setNegativeButton("Cancelar",null).setPositiveButton("Adicionar"){_,_-> if(edit.text.isNotBlank()) addPage(edit.text.toString()) }.show() } })
        pageList=TextView(this).apply { textSize=17f; setTextColor(0xff28634e.toInt()) }
        root.addView(pageList)
        root.addView(button("Organizar páginas") { if(!busy) organize() })
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
        try { val arr=JSONArray(getPreferences(MODE_PRIVATE).getString("pages","[]")); for(i in 0 until arr.length()) pages.add(arr.getString(i)) } catch(_:Exception) {}
        refreshPages()
        if(pages.isEmpty()) page=savedInstanceState?.getString("page") ?: ""
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
    private fun scan(bitmap:Bitmap) {
        pause(); busy=true; camera.isEnabled=false; status.text="Reconhecendo texto..."
        try {
            recognizer.process(InputImage.fromBitmap(bitmap,0))
                .addOnSuccessListener(this) { result ->
                    busy=false; camera.isEnabled=true
                    if(result.text.isBlank()) { status.text="Nenhum texto encontrado. Tente outra foto."; return@addOnSuccessListener }
                    addPage(result.text.trim())
                }.addOnFailureListener(this) { busy=false; camera.isEnabled=true; status.text="Falha no reconhecimento. Tente outra foto." }
        } catch(e:Exception) { busy=false; camera.isEnabled=true; status.text="Não foi possível abrir a foto." }
    }
    private fun styleCard(b:Button,bg:Int,fg:Int) {
        b.background=GradientDrawable().apply { setColor(bg); cornerRadius=dp(22).toFloat() }
        b.setTextColor(fg); b.textSize=19f
    }
    private fun addPage(text:String) {
        pause(); pages.add(text.trim()); refreshPages()
        status.text="Página ${pages.size} adicionada. Fotografe outra ou toque em Ouvir."
    }
    private fun refreshPages() {
        page=pages.joinToString("\n\n")
        offset=0
        reader.text=if(page.isBlank()) "O texto aparecerá aqui." else page
        pageList.text="${pages.size} página(s) na leitura"
        val arr=JSONArray(); pages.forEach { arr.put(it) }
        getPreferences(MODE_PRIVATE).edit().putString("pages",arr.toString()).apply()
    }
    private fun organize() {
        if(pages.isEmpty()) { status.text="Adicione uma página primeiro."; return }
        val labels=pages.mapIndexed { i,p -> "${i+1}. ${p.replace('\n',' ').take(65)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Páginas em ordem").setItems(labels){_,i->
            AlertDialog.Builder(this).setTitle("Página ${i+1}").setItems(arrayOf("Mover para cima","Mover para baixo","Excluir página","Ouvir a partir daqui")){_,op->
                pause()
                when(op) {
                    0 -> if(i>0) { java.util.Collections.swap(pages,i,i-1); refreshPages() }
                    1 -> if(i<pages.lastIndex) { java.util.Collections.swap(pages,i,i+1); refreshPages() }
                    2 -> { pages.removeAt(i); refreshPages() }
                    3 -> { offset=pages.take(i).sumOf { it.length+2 }; speak() }
                }
                if(op!=3) organize()
            }.show()
        }.setNegativeButton("Fechar",null).setNeutralButton("Nova leitura"){_,_->
            AlertDialog.Builder(this).setMessage("Apagar as páginas desta leitura?").setNegativeButton("Cancelar",null).setPositiveButton("Apagar"){_,_-> pause(); pages.clear(); refreshPages() }.show()
        }.show()
    }
    private fun crop(uri:Uri) {
        pause()
        busy=true; camera.isEnabled=false
        try {
            val bitmap=if(android.os.Build.VERSION.SDK_INT>=28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder,info,_ ->
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                val max=maxOf(info.size.width,info.size.height)
                if(max>3000) decoder.setTargetSize(info.size.width*3000/max,info.size.height*3000/max)
            } else contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: throw IllegalStateException("Imagem inválida")
            val view=CropView(bitmap)
            val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(TextView(this@MainActivity).apply { text="Arraste as bordas para selecionar o texto."; textSize=16f }); addView(view,LinearLayout.LayoutParams(-1,dp(420))) }
            fun cancel() { busy=false; camera.isEnabled=true; status.text="Foto cancelada." }
            AlertDialog.Builder(this).setTitle("Enquadre o texto").setView(layout).setNegativeButton("Refazer"){_,_-> cancel() }.setPositiveButton("Usar página"){_,_-> scan(view.cropped()) }.setOnCancelListener { cancel() }.show()
        } catch(e:Exception) { busy=false; camera.isEnabled=true; status.text="Não foi possível abrir a imagem." }
    }
    private inner class CropView(private val bitmap:Bitmap):View(this@MainActivity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val image=RectF()
        private val selection=RectF()
        private var edge=0
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            val scale=minOf(w.toFloat()/bitmap.width,h.toFloat()/bitmap.height)
            val iw=bitmap.width*scale; val ih=bitmap.height*scale
            image.set((w-iw)/2,(h-ih)/2,(w+iw)/2,(h+ih)/2)
            selection.set(image); selection.inset(iw*.05f,ih*.05f)
        }
        override fun onDraw(c:Canvas) {
            c.drawBitmap(bitmap,null,image,paint)
            paint.color=0x88000000.toInt(); paint.style=Paint.Style.FILL
            c.drawRect(image.left,image.top,image.right,selection.top,paint)
            c.drawRect(image.left,selection.bottom,image.right,image.bottom,paint)
            c.drawRect(image.left,selection.top,selection.left,selection.bottom,paint)
            c.drawRect(selection.right,selection.top,image.right,selection.bottom,paint)
            paint.color=0xff368266.toInt(); paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(3).toFloat(); c.drawRect(selection,paint)
            paint.style=Paint.Style.FILL
            for(x in listOf(selection.left,selection.right)) for(y in listOf(selection.top,selection.bottom)) c.drawCircle(x,y,dp(8).toFloat(),paint)
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            val x=e.x; val y=e.y
            if(e.action==MotionEvent.ACTION_DOWN) {
                edge=0
                if(kotlin.math.abs(x-selection.left)<dp(40)) edge=edge or 1
                if(kotlin.math.abs(x-selection.right)<dp(40)) edge=edge or 2
                if(kotlin.math.abs(y-selection.top)<dp(40)) edge=edge or 4
                if(kotlin.math.abs(y-selection.bottom)<dp(40)) edge=edge or 8
                return true
            }
            if(e.action==MotionEvent.ACTION_MOVE) {
                val minW=minOf(dp(32).toFloat(),image.width()/2); val minH=minOf(dp(32).toFloat(),image.height()/2)
                if(edge and 1 !=0) selection.left=x.coerceIn(image.left,selection.right-minW)
                if(edge and 2 !=0) selection.right=x.coerceIn(selection.left+minW,image.right)
                if(edge and 4 !=0) selection.top=y.coerceIn(image.top,selection.bottom-minH)
                if(edge and 8 !=0) selection.bottom=y.coerceIn(selection.top+minH,image.bottom)
                invalidate(); return true
            }
            return true
        }
        fun cropped():Bitmap {
            val x=((selection.left-image.left)/image.width()*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
            val y=((selection.top-image.top)/image.height()*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
            val w=(selection.width()/image.width()*bitmap.width).toInt().coerceIn(1,bitmap.width-x)
            val h=(selection.height()/image.height()*bitmap.height).toInt().coerceIn(1,bitmap.height-y)
            return Bitmap.createBitmap(bitmap,x,y,w,h)
        }
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
