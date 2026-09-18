package io.github.jqssun.airplay.files;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded LAN-only HTTP upload. Browser access requires the token displayed on the TV. */
final class BrowserReceiver implements Closeable {
    interface Approval { boolean accept(String sender, Map<String,Boolean> files) throws Exception; }
    interface Received { void complete(File directory); }
    interface Progress { void changed(TransferProgress state); }
    private final ServerSocket server;
    private final File root;
    private final FileStore storage;
    private final java.util.function.BooleanSupplier downloads;
    private final Approval approval;
    private final Received received;
    private final Progress progress;
    private volatile TransferProgress state;
    TransferProgress progress() { return state; }
    private void publish(TransferProgress value) { state=value;progress.changed(value); }
    final String token;
    private volatile boolean requirePin=true;
    private final Map<String,long[]> attempts=new LinkedHashMap<>();
    private volatile boolean closed;
    private final Set<Socket> sockets=Collections.synchronizedSet(new HashSet<>());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private volatile Offer offer;
    private volatile boolean asking;
    boolean isBusy() { return asking || offer!=null; }
    private static final class Offer {
        final String id=UUID.randomUUID().toString();
        final List<String> names=new ArrayList<>();
        final List<Long> sizes=new ArrayList<>();
        FileStore.Session session;
        int next;
        long total,done,lastPublished;
        TransferProgress.Meter meter;
        volatile boolean cancelled;
        volatile Socket uploadSocket;
        long expires=System.currentTimeMillis()+300000;
    }
    BrowserReceiver(FileStore storage,java.util.function.BooleanSupplier downloads,Approval approval,Received received,Progress progress) throws IOException {
        this.storage=storage;this.downloads=downloads;this.root=storage.root;this.approval=approval;this.received=received;this.progress=progress;
        File root=this.root;
        if(!root.isDirectory() && !root.mkdirs()) throw new IOException("Storage unavailable");
        token=String.format(Locale.ROOT,"%04d",new SecureRandom().nextInt(10000));
        // Remove only interrupted transfers owned by this receiver.
        File[] stale=root.listFiles((directory,name)->name.startsWith(".web-partial-"));
        if(stale!=null) for(File file:stale) remove(file);
        server=new ServerSocket(); server.setReuseAddress(true); server.bind(new InetSocketAddress(8786));
    }
    void setRequirePin(boolean value) { requirePin=value; }
    boolean requiresPin() { return requirePin; }
    private synchronized boolean authenticate(String peer,String value) {
        if(!requirePin) return true;
        long now=System.currentTimeMillis(); long[] state=attempts.get(peer);
        if(state!=null && now-state[0]<60000 && state[1]>=5) return false;
        if(MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.US_ASCII),value.getBytes(StandardCharsets.US_ASCII))) { attempts.remove(peer); return true; }
        if(state==null || now-state[0]>=60000) {
            if(attempts.size()>=64) attempts.remove(attempts.keySet().iterator().next());
            state=new long[]{now,0}; attempts.put(peer,state);
        }
        state[1]++; return false;
    }
    void start() {
        timer.scheduleAtFixedRate(()->{ synchronized(this) { expire(); } },30,30,TimeUnit.SECONDS);
        new Thread(()->{
            while(!closed) try {
                Socket socket=server.accept(); socket.setSoTimeout(60000); sockets.add(socket);
                try { workers.execute(()->handle(socket)); }
                catch(RejectedExecutionException busy) { sockets.remove(socket); socket.close(); }
            } catch(IOException error) { if(!closed) android.util.Log.w("AirTVFiles","Browser listener failed",error); }
        },"AirTV-browser-listener").start();
    }
    private static String line(InputStream input,int maximum) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int previous=-1;
        while(bytes.size()<maximum) { int value=input.read(); if(value<0) throw new EOFException();
            if(previous==13 && value==10) { byte[] raw=bytes.toByteArray(); return new String(raw,0,raw.length-1,StandardCharsets.US_ASCII); }
            if(value==10 || (previous==13 && value!=10)) throw new IOException("Invalid header");
            bytes.write(value); previous=value;
        } throw new IOException("Header too large");
    }
    private static void response(OutputStream output,int status,String type,byte[] data) throws IOException {
        String reason=status==200?"OK":status==403?"Forbidden":status==409?"Conflict":"Bad Request";
        output.write(("HTTP/1.1 "+status+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+
            "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"+
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(data); output.flush();
    }
    private static byte[] json(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private void handle(Socket socket) {
        Offer uploading=null;
        try {
            Socket connection=socket;
            InputStream input=new BufferedInputStream(connection.getInputStream()); OutputStream output=connection.getOutputStream();
            String[] request=line(input,4096).split(" ");
            if(request.length!=3 || !request[2].equals("HTTP/1.1")) throw new IOException("Invalid request");
            Map<String,String> headers=new HashMap<>(); int total=0;
            for(;;) { String header=line(input,4096); total+=header.length(); if(total>16384) throw new IOException("Headers too large"); if(header.isEmpty()) break;
                int colon=header.indexOf(':'); if(colon<1) throw new IOException("Invalid header");
                String name=header.substring(0,colon).toLowerCase(Locale.ROOT);
                if(headers.put(name,header.substring(colon+1).trim())!=null) throw new IOException("Duplicate header");
            }
            String host=headers.get("host"), origin=headers.get("origin");
            if(host==null || !host.endsWith(":8786") || (origin!=null && !origin.equals("http://"+host))) {
                response(output,403,"application/json",json("{\"error\":\"Недопустимый адрес отправителя\"}")); return;
            }
            if(request[0].equals("GET") && request[1].equals("/")) {
                response(output,200,"text/html; charset=utf-8",json(PAGE.replace("__PIN_REQUIRED__",String.valueOf(requirePin)).replace("__STORAGE__",downloads.getAsBoolean()?"Downloads":"Память Air TV"))); return;
            }
            if((!requirePin && origin==null) || !authenticate(connection.getInetAddress().getHostAddress(),headers.getOrDefault("authorization",""))) {
                response(output,403,"application/json",json("{\"error\":\"Неверный PIN или слишком много попыток. Попробуйте через минуту.\"}")); return;
            }
            if(headers.containsKey("transfer-encoding") || headers.containsKey("expect")) throw new IOException("Unsupported framing");
            String lengthText=headers.get("content-length");
            if(lengthText==null || !lengthText.matches("[0-9]{1,19}")) throw new IOException("Missing length");
            long length=Long.parseLong(lengthText);
            if(request[0].equals("POST") && request[1].equals("/offer")) {
                if(length<1 || length>32768) throw new IOException("Offer too large");
                byte[] body=new byte[(int)length]; new DataInputStream(input).readFully(body);
                JSONObject description=new JSONObject(new String(body,StandardCharsets.UTF_8)); JSONArray files=description.getJSONArray("files");
                if(files.length()<1 || files.length()>128) throw new IOException("Too many files");
                Offer proposed=new Offer(); Map<String,Boolean> names=new LinkedHashMap<>(); long bytes=0;
                for(int i=0;i<files.length();i++) {
                    JSONObject file=files.getJSONObject(i); String name=file.getString("name"); long size=file.getLong("size");
                    if(name.isEmpty() || name.equals(".") || name.equals("..") || name.startsWith(".web-partial-") || name.contains("/") || name.contains("\\") || name.indexOf(0)>=0 || name.getBytes(StandardCharsets.UTF_8).length>255 || names.containsKey(name)) throw new IOException("Invalid file name");
                    if(size<0 || bytes>Long.MAX_VALUE-size) throw new IOException("Files too large");
                    for(int c=0;c<name.length();c++) if(Character.isISOControl(name.charAt(c))) throw new IOException("Invalid file name");
                    bytes+=size; names.put(name,false); proposed.names.add(name); proposed.sizes.add(size);
                }
                boolean targetDownloads=downloads.getAsBoolean();
                if(bytes>storage.available(targetDownloads)) { response(output,409,"application/json",json("{\"error\":\"На телевизоре недостаточно свободного места\"}")); return; }
                synchronized(this) { expire(); if(offer!=null || asking) { response(output,409,"application/json",json("{\"error\":\"Телевизор занят другой передачей\"}")); return; } asking=true; }
                try {
                    if(!approval.accept("Браузер · "+connection.getInetAddress().getHostAddress(),names)) {
                        response(output,403,"application/json",json("{\"error\":\"Передача отклонена на телевизоре\"}")); return;
                    }
                    synchronized(this) {
                        if(closed) throw new IOException("Stopped");
                        proposed.session=storage.begin(proposed.names,targetDownloads);proposed.total=bytes;
                        proposed.meter=new TransferProgress.Meter(bytes,System.nanoTime());offer=proposed;
                        publish(proposed.meter.sample(proposed.names.get(0),"Принимаем",1,proposed.names.size(),0,System.nanoTime()));
                    }
                } finally { synchronized(this) { asking=false; } }
                response(output,200,"application/json",json("{\"id\":\""+proposed.id+"\"}")); return;
            }
            String[] path=request[1].split("/");
            synchronized(this) {
                expire();
                if(path.length<3 || offer==null || !offer.id.equals(path[2])) throw new IOException("Transfer expired");
                Offer active=offer;
                if(request[0].equals("PUT") && path.length==4 && path[1].equals("upload")) {
                    int index=Integer.parseInt(path[3]);
                    if(index!=active.next || index>=active.names.size() || length!=active.sizes.get(index)) throw new IOException("Unexpected file");
                    if(active.cancelled) throw new IOException("Cancelled");
                    uploading=active;active.uploadSocket=socket;
                    publish(active.meter.sample(active.names.get(index),"Принимаем",index+1,active.names.size(),active.done,System.nanoTime()));
                    try(OutputStream file=active.session.open(index,length)) {
                        byte[] buffer=new byte[65536]; long remaining=length;
                        while(remaining>0) { int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining)); if(count<0) throw new EOFException(); if(active.cancelled) throw new IOException("Cancelled");
                            file.write(buffer,0,count); remaining-=count;
                            long now=System.nanoTime();
                            if(now-active.lastPublished>=250000000L) {
                                active.lastPublished=now;active.expires=System.currentTimeMillis()+300000;
                                publish(active.meter.sample(active.names.get(index),"Принимаем",index+1,active.names.size(),active.done+length-remaining,now));
                            } }
                    }
                    active.uploadSocket=null;if(active.cancelled) throw new IOException("Cancelled");
                    active.done+=length;
                    publish(active.meter.sample(active.names.get(index),"Принимаем",index+1,active.names.size(),active.done,System.nanoTime()));
                    active.next++; active.expires=System.currentTimeMillis()+300000; uploading=null;
                    response(output,200,"application/json",json("{\"ok\":true}")); return;
                }
                if(request[0].equals("POST") && path.length==3 && path[1].equals("finish") && length==0) {
                    if(active.next!=active.names.size()) throw new IOException("Incomplete transfer");
                    if(active.cancelled) throw new IOException("Cancelled");
                    uploading=active;
                    publish(new TransferProgress("", "Проверяем и сохраняем",active.names.size(),active.names.size(),active.done,active.total,0));
                    JSONArray hashes=active.session.checksums();
                    if(active.cancelled) throw new IOException("Cancelled");
                    active.session.finish();
                    offer=null;uploading=null;publish(null); received.complete(root);
                    response(output,200,"application/json",json(new JSONObject().put("ok",true).put("files",hashes).toString())); return;
                }
                if(request[0].equals("POST") && path.length==3 && path[1].equals("cancel") && length==0) {
                    active.session.cancel(); offer=null;publish(null); response(output,200,"application/json",json("{\"ok\":true}")); return;
                }
                throw new IOException("Unknown endpoint");
            }
        } catch(Exception error) {
            synchronized(this) { if(uploading!=null && uploading==offer) { offer.session.cancel(); offer=null;publish(null); } }
            // A malformed request is closed without ever committing its partial files.
            android.util.Log.i("AirTVFiles","Browser request rejected: "+error.getClass().getSimpleName());
            try { response(socket.getOutputStream(),400,"application/json",json("{\"error\":\"Не удалось принять передачу. Проверь имена, размер файлов и подключение.\"}")); }
            catch(IOException ignored) {}
        } finally { sockets.remove(socket); try { socket.close(); } catch(IOException ignored) {} }
    }
    private void expire() { if(offer!=null && System.currentTimeMillis()>offer.expires) { offer.session.cancel(); offer=null;publish(null); } }
    void cancelTransfer() {
        Offer active=offer;if(active==null)return;
        active.cancelled=true;Socket socket=active.uploadSocket;
        if(socket!=null) try { socket.close(); } catch(IOException ignored) {}
        // Disk cleanup is asynchronous so cancelling never freezes the remote control/UI.
        new Thread(()->{ synchronized(this) { if(offer==active) { active.session.cancel();offer=null;publish(null); } } },"AirTV-upload-cancel").start();
    }
    private static void remove(File file) { if(file==null) return; File[] children=file.listFiles(); if(children!=null) for(File child:children) remove(child); file.delete(); }
    public void close() {
        closed=true; try { server.close(); } catch(IOException ignored) {}
        synchronized(sockets) { for(Socket socket:sockets) try { socket.close(); } catch(IOException ignored) {} }
        workers.shutdownNow(); timer.shutdownNow();
        synchronized(this) { if(offer!=null) offer.session.cancel(); offer=null;publish(null); }
    }
    private static final String PAGE="<!doctype html><html lang='ru'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Air TV · AirDrop</title>"+
        "<style>*{box-sizing:border-box}body{margin:0;background:#0c1216;color:#ecf3f1;font:16px system-ui;min-height:100vh;display:grid;place-items:center}main{width:min(560px,calc(100% - 40px));padding:48px 0}h1{color:#9aefcf;font-size:36px;letter-spacing:-1px;margin:0 0 12px}p{color:#aabbb8;line-height:1.6}.drop{border:1px dashed #59716b;border-radius:20px;padding:40px 24px;text-align:center;margin:28px 0 16px;cursor:pointer}.drop.over{background:#19332a;border-color:#9aefcf}button{background:#9aefcf;color:#12211b;border:0;border-radius:12px;padding:14px 24px;font:600 16px system-ui;cursor:pointer}button:disabled{opacity:.4;cursor:default}ul{padding-left:20px;overflow-wrap:anywhere}progress{width:100%;accent-color:#9aefcf;margin:20px 0 8px}#status{min-height:48px}.small{font-size:13px;color:#849a93}#pick{display:none}#code{background:#17231f;border:1px solid #59716b;border-radius:10px;padding:12px;color:#ecf3f1;font:18px ui-monospace;width:100%;margin:8px 0}label{color:#aabbb8}</style>"+
        "<main><h1>Air TV</h1><p>Передай файлы на телевизор.<br>Mac и телевизор должны быть в одной сети.</p><label id='codeLabel' for='code'>PIN на экране телевизора</label><input id='code' placeholder='1234' inputmode='numeric' autocomplete='off' maxlength='4'><div class='drop' id='drop' role='button' tabindex='0'>Перетащи файлы сюда<br><span class='small'>или нажми, чтобы выбрать</span></div><input id='pick' type='file' multiple><ul id='list'></ul><button id='send' disabled>Отправить на телевизор</button><progress id='progress' max='100' value='0'></progress><p id='status' aria-live='polite'></p><p class='small'>Сохранять: __STORAGE__</p><p class='small'>Подтверди приём на телевизоре.<br>Это загрузка через локальную сеть. AirDrop в Finder пока не поддерживается. Соединение HTTP не шифруется.</p></main>"+
        "<script>const requirePin=__PIN_REQUIRED__;let token=location.hash.slice(1);const code=document.getElementById('code');code.value=token;if(!requirePin){code.hidden=true;document.getElementById('codeLabel').hidden=true}const pick=document.getElementById('pick'),drop=document.getElementById('drop'),send=document.getElementById('send'),list=document.getElementById('list'),status=document.getElementById('status'),progress=document.getElementById('progress');let files=[],busy=false;history.replaceState(null,'',location.pathname);"+
        "function choose(selected){if(busy)return;token=code.value.replace(/[-\\s]/g,'').toLowerCase();files=Array.from(selected);list.replaceChildren();for(const f of files){const li=document.createElement('li');li.textContent=f.name+' · '+(f.size/1048576).toFixed(1)+' МБ';list.append(li)}const total=files.reduce((n,f)=>n+f.size,0);send.disabled=(requirePin&&!/^[0-9]{4}$/.test(token))||!files.length||files.length>128;status.textContent=send.disabled?'Выбери до 128 файлов.':'Готово к отправке';progress.value=0}"+
        "code.oninput=()=>choose(files);drop.onclick=()=>{if(!busy)pick.click()};drop.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();drop.click()}};pick.onchange=()=>choose(pick.files);drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};drop.ondragleave=()=>drop.classList.remove('over');drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');choose(e.dataTransfer.files)};"+
        "async function api(path,body){const r=await fetch(path,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body===undefined?'':JSON.stringify(body)});const result=await r.json();if(!r.ok)throw Error(result.error||'Телевизор отклонил запрос');return result}"+
        "function upload(id,i,file,done,total){return new Promise((resolve,reject)=>{const x=new XMLHttpRequest();x.open('PUT','/upload/'+id+'/'+i);x.setRequestHeader('Authorization','Bearer '+token);x.timeout=0;x.upload.onprogress=e=>{progress.value=total?100*(done+e.loaded)/total:100};x.onload=()=>x.status===200?resolve():reject(Error('Файл не принят телевизором'));x.onerror=()=>reject(Error('Связь с телевизором прервалась'));x.ontimeout=()=>reject(Error('Истекло время загрузки'));x.send(file)})}"+
        "send.onclick=async()=>{busy=true;code.disabled=true;send.disabled=true;let id;try{status.textContent='Подтверди приём на телевизоре…';const offer=await api('/offer',{files:files.map(f=>({name:f.name,size:f.size}))});id=offer.id;let done=0,total=files.reduce((n,f)=>n+f.size,0);for(let i=0;i<files.length;i++){status.textContent='Отправляем '+files[i].name;await upload(id,i,files[i],done,total);done+=files[i].size}await api('/finish/'+id);id=null;progress.value=100;status.textContent='Готово. Файлы сохранены на телевизоре.'}catch(e){status.textContent=e.message;if(id)try{await api('/cancel/'+id)}catch(_){} }finally{busy=false;code.disabled=false;send.disabled=false}};status.textContent=requirePin?'Введи PIN с экрана телевизора и выбери файлы.':'Выбери файлы. PIN отключён в настройках телевизора.';</script></html>";
}
