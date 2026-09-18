package io.github.jqssun.airplay.files

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.jqssun.airplay.MainActivity
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.ui.AirTvNavigation
import io.github.jqssun.airplay.ui.dpadFocus
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/** AirDrop uses the same theme, controls and bottom navigation as AirPlay. */
class FilesActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var visible = false
    private var status by mutableStateOf("Запуск приёма…")
    private var transfer by mutableStateOf<TransferProgress?>(null)
    private var running by mutableStateOf(false)
    private var files by mutableStateOf<List<FileStore.Entry>>(emptyList())
    private var pending by mutableStateOf<FilesReceiver.Pending?>(null)
    private var qr by mutableStateOf<Bitmap?>(null)
    private var qrAddress = ""
    private var signature = ""
    private var deleting by mutableStateOf<FileStore.Entry?>(null)
    private var textPreview by mutableStateOf<Pair<String,String>?>(null)
    private var imagePreview by mutableStateOf<Pair<String,Bitmap>?>(null)
    private val poll = object : Runnable { override fun run() { if (visible) { refresh(); main.postDelayed(this,3000) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AirPlayTheme { Screen() } }
        startReceiver()
    }
    private fun startReceiver() = ContextCompat.startForegroundService(this, Intent(this,AirPlayService::class.java).setAction(AirPlayService.ACTION_START_SERVER))
    private fun home(settings: Boolean) {
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_settings",settings)); finish()
    }
    override fun onResume() { super.onResume();visible=true;FilesReceiver.attach(this);main.removeCallbacks(poll);poll.run() }
    override fun onPause() { visible=false;main.removeCallbacks(poll);FilesReceiver.detach(this);super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);refresh() }
    fun refresh() {
        if (!visible || isFinishing) return
        val receiver=FilesReceiver.current();running=receiver!=null;status=receiver?.status()?:"Приём выключен";pending=receiver?.pending();transfer=receiver?.progress()
        if(receiver!=null && receiver.url()!=qrAddress) try {
            val address=receiver.url();val matrix=QRCodeWriter().encode(address,BarcodeFormat.QR_CODE,384,384)
            val pixels=IntArray(384*384) { index-> if(matrix[index%384,index/384]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            qr=Bitmap.createBitmap(pixels,384,384,Bitmap.Config.ARGB_8888);qrAddress=address
        }catch(error:Exception){android.util.Log.w("AirTVFiles","QR unavailable",error)}
        try {
            val entries=FileStore.get(this).list();val current=entries.joinToString(";") { "${it.id}:${it.size}:${it.time}" }
            if(current!=signature){signature=current;files=entries}
        }catch(error:IOException){status="Не удалось прочитать сохранённые файлы."}
    }
    @Composable private fun Screen() {
        val focus=remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocusUntilLanded() }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal=28.dp,vertical=16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Cast,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(24.dp))
                    Text("air tv",fontSize=22.sp,fontWeight=FontWeight.Medium,letterSpacing=1.sp)
                }
                Text(if(transfer!=null) "Принимаем файлы" else if(running) "Готов к подключению" else "Приём выключен",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Передать файлы",fontSize=27.sp,fontWeight=FontWeight.Normal)
                    Spacer(Modifier.height(12.dp))
                    Text("Сканируй QR камерой телефона\nили открой адрес в браузере на Mac.",fontSize=15.sp,lineHeight=23.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp));Text(status,fontSize=16.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.primary)
                }
                qr?.let { Image(it.asImageBitmap(),"QR-код адреса передачи файлов",Modifier.size(176.dp).background(Color.White).padding(8.dp)) }
            }
            transfer?.let { progress->
                Spacer(Modifier.height(16.dp))
                Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if(progress.phase=="Принимаем") "${progress.name} · ${progress.file} из ${progress.files}" else progress.phase,
                                fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            if(progress.phase=="Принимаем") LinearProgressIndicator(progress={progress.fraction()},modifier=Modifier.fillMaxWidth(),trackColor=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.2f))
                            else LinearProgressIndicator(modifier=Modifier.fillMaxWidth(),trackColor=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.2f))
                            Spacer(Modifier.height(8.dp))
                            val remaining=progress.remainingSeconds()
                            Text("${(progress.fraction()*100).toInt()}% · ${bytes(progress.received)} / ${bytes(progress.total)}"+
                                (if(progress.bytesPerSecond>0) " · ${bytes(progress.bytesPerSecond.toLong())}/с" else "")+
                                (if(remaining>=0 && progress.phase=="Принимаем") " · осталось ${duration(remaining)}" else ""),
                                fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick={FilesReceiver.current()?.cancelTransfer()},enabled=progress.phase=="Принимаем",modifier=Modifier.dpadFocus()) {Text("Отменить")}
                    }
                }
            }
            if(transfer!=null) Spacer(Modifier.weight(1f)) else {
            Spacer(Modifier.height(20.dp));Text("Сохранённые файлы",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(8.dp))
            if(files.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.CenterStart) {
                Text("Пока нет файлов",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(files,key={it.id}) { entry->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        FilledTonalButton(onClick={open(entry)},modifier=Modifier.weight(1f).dpadFocus(),shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(14.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(entry.name,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(String.format(Locale.ROOT,"%.1f МБ · %s",entry.size/1048576.0,entry.location),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedButton(onClick={deleting=entry},modifier=Modifier.dpadFocus(),shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=22.dp,vertical=14.dp)) {Text("Удалить")}
                    }
                }
            }
            }
            Spacer(Modifier.height(12.dp))
            AirTvNavigation("AirDrop",running,onSettings={home(true)},onAirPlay={home(false)},onAirDrop={},
                onToggleReceiver={ if(running) AirPlayService.stopReceiver() else startReceiver() },initialFocus=focus)
            Spacer(Modifier.height(12.dp))
            Text("Файлы через браузер · AirDrop в Finder в разработке",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.align(Alignment.End))
        }
        pending?.let { request->
            val declineFocus=remember(request){FocusRequester()};LaunchedEffect(request){declineFocus.requestFocusUntilLanded()}
            AlertDialog(onDismissRequest={request.result.complete(false)},title={Text("Принять передачу?")},
                text={Text("Устройство: ${request.sender}\n\nФайлы:\n"+request.files.keys.take(8).joinToString("\n")+(if(request.files.size>8)"\n… всего ${request.files.size}" else ""))},
                confirmButton={TextButton(onClick={request.result.complete(true)},modifier=Modifier.dpadFocus()){Text("Принять")}},
                dismissButton={TextButton(onClick={request.result.complete(false)},modifier=Modifier.focusRequester(declineFocus).dpadFocus()){Text("Отклонить")}})
        }
        deleting?.let { entry->
            AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить файл?")},text={Text("${entry.name}\n${entry.location}")},
                confirmButton={TextButton(onClick={deleting=null;Thread({try {FileStore.get(this).delete(entry);main.post {signature="#refresh";refresh()} }catch(error:IOException){error("Не удалось удалить файл.")}},"AirTV-file-delete").start()},modifier=Modifier.dpadFocus()){Text("Удалить")}},
                dismissButton={TextButton(onClick={deleting=null},modifier=Modifier.dpadFocus()){Text("Отмена")}})
        }
        textPreview?.let { (name,value)-> AlertDialog(onDismissRequest={textPreview=null},title={Text(name)},text={Text(value)},confirmButton={TextButton(onClick={textPreview=null},modifier=Modifier.dpadFocus()){Text("Закрыть")}}) }
        imagePreview?.let { (name,image)-> AlertDialog(onDismissRequest={closeImage()},title={Text(name)},text={Image(image.asImageBitmap(),name)},confirmButton={TextButton(onClick={closeImage()},modifier=Modifier.dpadFocus()){Text("Закрыть")}}) }
    }
    private fun bytes(value:Long):String = if(value>=1073741824) String.format(Locale.ROOT,"%.1f ГБ",value/1073741824.0) else String.format(Locale.ROOT,"%.1f МБ",value/1048576.0)
    private fun duration(seconds:Long):String = if(seconds>=60) "${seconds/60} мин ${seconds%60} с" else "$seconds с"
    private fun closeImage() { imagePreview=null }
    private fun error(value:String) {main.post {if(visible)textPreview="Air TV" to value} }
    private fun open(entry:FileStore.Entry) {
        val name=entry.name.lowercase(Locale.ROOT)
        if(name.matches(Regex(".*\\.(mp4|m4v|mkv|webm|mov|mpeg|mpg|avi|mp3|m4a|aac|wav|ogg)$"))){startActivity(Intent(this,PlaybackActivity::class.java).putExtra("entry_id",entry.id));return}
        Thread({try {
            val store=FileStore.get(this)
            if(name.matches(Regex(".*\\.(txt|md|log)$"))&&entry.size<=65536){
                val bytes=ByteArrayOutputStream();store.open(entry).use {input->val buffer=ByteArray(8192);while(true){val count=input.read(buffer);if(count<0)break;if(bytes.size()+count>65536)throw IOException("Text too large");bytes.write(buffer,0,count)}}
                val value=bytes.toString("UTF-8");main.post {if(visible)textPreview=entry.name to value}
            }else if(name.matches(Regex(".*\\.(png|jpg|jpeg|webp|gif|bmp)$"))){
                val options=BitmapFactory.Options();options.inJustDecodeBounds=true;store.open(entry).use {BitmapFactory.decodeStream(it,null,options)}
                options.inSampleSize=1;while(options.outWidth/options.inSampleSize>1920||options.outHeight/options.inSampleSize>1080)options.inSampleSize*=2;options.inJustDecodeBounds=false
                val image=store.open(entry).use {BitmapFactory.decodeStream(it,null,options)}?:throw IOException("Invalid image");main.post {if(visible)imagePreview=entry.name to image else image.recycle()}
            }else error("Файл сохранён. Просмотр этого формата пока не поддерживается.")
        }catch(error:IOException){error("Не удалось открыть файл.")}},"AirTV-file-open").start()
    }
    override fun onDestroy(){visible=false;main.removeCallbacks(poll);FilesReceiver.detach(this);super.onDestroy()}
}
