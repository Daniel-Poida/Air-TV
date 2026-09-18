package dev.airtv.airdrop.lab;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.net.nsd.*;
import android.security.keystore.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import dev.airtv.airdrop.AirDropReceiver;

/** Separate experimental app. No boot, overlay or foreground-service permissions. */
public final class LabActivity extends Activity {
    private static final String TAG="AirDropLab";
    private static final String ALIAS="airtv-airdrop-lab-tls-v2";
    private volatile AirDropReceiver receiver;
    private volatile BrowserReceiver browser;
    private volatile boolean stopped;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private TextView status;
    private LinearLayout fileList;
    private volatile String pairingCode="";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(64,48,64,40); layout.setBackgroundColor(Color.rgb(12,18,22));
        TextView heading=new TextView(this); heading.setText("Air TV · AirDrop Lab"); heading.setTextSize(32);
        heading.setText("Air TV · Передача файлов");
        heading.setTextColor(Color.rgb(154,239,207)); layout.addView(heading);
        TextView description=new TextView(this);
        description.setText("Открой адрес ниже в браузере на Mac.\nВыбери файлы и подтверди приём на телевизоре.\nЭкспериментальная сборка · AirDrop в Finder пока не готов.");
        description.setTextSize(19); description.setTextColor(Color.LTGRAY); description.setPadding(0,24,0,28); layout.addView(description);
        Switch requirePin=new Switch(this); requirePin.setText("Требовать PIN в браузере"); requirePin.setTextColor(Color.WHITE);
        requirePin.setChecked(getPreferences(MODE_PRIVATE).getBoolean("browser_pin",true));
        requirePin.setOnCheckedChangeListener((button,value)->{
            getPreferences(MODE_PRIVATE).edit().putBoolean("browser_pin",value).apply();
            BrowserReceiver current=browser; if(current!=null) current.setRequirePin(value);
            update(browserStatus()+"\nОбнови страницу браузера после изменения настройки.");
        }); layout.addView(requirePin);
        status=new TextView(this); status.setText("Запуск TLS и Bonjour…"); status.setTextSize(18); status.setTextColor(Color.WHITE);
        layout.addView(status,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(this);
        fileList=new LinearLayout(this); fileList.setOrientation(LinearLayout.VERTICAL); scroll.addView(fileList);
        layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Button stop=new Button(this); stop.setText("Остановить тест"); stop.setOnClickListener(v -> finish()); layout.addView(stop);
        setContentView(layout); stop.requestFocus();
        refreshFiles();
        nsd=(NsdManager)getSystemService(NSD_SERVICE);
        new Thread(() -> startReceiver(),"AirDrop-lab-start").start();
    }

    private void update(String text) { runOnUiThread(() -> { if (!stopped) status.setText(text); }); }

    private void completed(File directory) {
        update(browserStatus()+"\n\nПередача завершена. Выбери файл ниже.");
        runOnUiThread(this::refreshFiles);
    }

    private void refreshFiles() {
        if(stopped || fileList==null) return;
        fileList.removeAllViews();
        File[] directories=new File(getFilesDir(),"received").listFiles();
        if(directories==null) return;
        Arrays.sort(directories,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        int count=0;
        for(File directory:directories) {
            if(!directory.isDirectory() || directory.getName().startsWith(".")) continue;
            File[] files=directory.listFiles(); if(files==null) continue;
            for(File file:files) {
                if(!file.isFile()) continue;
                if(++count>30) return;
                Button open=new Button(this); open.setAllCaps(false); open.setText(file.getName());
                open.setOnClickListener(v -> {
                    String name=file.getName().toLowerCase(Locale.ROOT);
                    if(name.matches(".*\\.(mp4|m4v|mkv|webm|mov|mpeg|mpg|avi|mp3|m4a|aac|wav|ogg)$")) {
                        startActivity(new android.content.Intent(this,PlaybackActivity.class).putExtra("path",file.getAbsolutePath()));
                    } else if(name.matches(".*\\.(txt|md|log)$") && file.length()<=65536) {
                        try {
                            byte[] data=new byte[(int)file.length()];
                            try(DataInputStream input=new DataInputStream(new FileInputStream(file))) { input.readFully(data); }
                            new AlertDialog.Builder(this).setTitle(file.getName()).setMessage(new String(data,java.nio.charset.StandardCharsets.UTF_8)).setPositiveButton("Закрыть",null).show();
                        } catch(IOException error) { update("Не удалось открыть файл."); }
                    } else {
                        new AlertDialog.Builder(this).setTitle(file.getName()).setMessage("Файл сохранён. Просмотр этого формата в тестовой сборке пока не реализован.").setPositiveButton("Закрыть",null).show();
                    }
                });
                fileList.addView(open);
            }
        }
    }

    private SSLContext context() throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (!keys.containsAlias(ALIAS)) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
            // Conscrypt uses prehashed signatures/raw private-key operations for TLS.
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512,KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(new X500Principal("CN=Air TV AirDrop Lab"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(new Date(1577836800000L)).setCertificateNotAfter(new Date(4070908800000L)).build());
            generator.generateKeyPair();
        }
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys,null);
        X509TrustManager peers=new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain,String type) throws CertificateException {
                if (chain==null || chain.length==0) throw new CertificateException(); chain[0].checkValidity();
            }
            public void checkServerTrusted(X509Certificate[] chain,String type) throws CertificateException { throw new CertificateException(); }
        };
        SSLContext context=SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(),new TrustManager[]{peers},new SecureRandom());
        byte[] fingerprint=MessageDigest.getInstance("SHA-256").digest(keys.getCertificate(ALIAS).getEncoded());
        StringBuilder hex=new StringBuilder();
        for(byte value:fingerprint) hex.append(String.format(Locale.ROOT,"%02X",value&255));
        pairingCode=hex.substring(0,12);
        Log.i(TAG,"TLS_CERT_SHA256 "+hex);
        return context;
    }

    private void startReceiver() {
        try {
            BrowserReceiver web=new BrowserReceiver(new File(getFilesDir(),"received"),this::approve,this::completed);
            synchronized(this) {
                if(stopped) { web.close(); return; }
                web.setRequirePin(getPreferences(MODE_PRIVATE).getBoolean("browser_pin",true));
                browser=web; web.start();
            }
            AirDropReceiver current=new AirDropReceiver(context(),InetAddress.getByName("::"),8785,"Air TV Lab 31",
                new File(getFilesDir(),"received"),this::approve,
                this::completed,
                new AirDropReceiver.Observer() {
                    public void request(String path) { Log.i(TAG,"Request "+path); }
                    public void transportError(Exception error) { Log.e(TAG,"TLS/transport failure",error); }
                });
            synchronized(this) {
                if (stopped) { current.close(); return; }
                receiver=current; current.start();
            }
            runOnUiThread(() -> advertise(current.port()));
        } catch (Exception error) { update("Не удалось запустить тест: "+error.getClass().getSimpleName()); Log.e(TAG,"Startup failure",error); }
    }

    private String browserStatus() {
        String address="IP телевизора";
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()) {
                NetworkInterface face=interfaces.nextElement();
                if(!face.isUp() || face.isLoopback()) continue;
                Enumeration<InetAddress> addresses=face.getInetAddresses();
                while(addresses.hasMoreElements()) { InetAddress ip=addresses.nextElement();
                    if(ip instanceof Inet4Address && ip.isSiteLocalAddress()) { address=ip.getHostAddress(); break; }
                }
                if(!address.equals("IP телевизора")) break;
            }
        } catch(Exception ignored) {}
        BrowserReceiver current=browser;
        String code=current==null?"…":current.token;
        String pin=current!=null && !current.requiresPin()?"PIN отключён":"PIN: "+code;
        return "Адрес: http://"+address+":8786\n"+pin+"\nОставь это приложение открытым во время передачи.";
    }

    private boolean approve(String sender,Map<String,Boolean> files) throws Exception {
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        final AlertDialog[] shown=new AlertDialog[1];
        runOnUiThread(() -> {
            if (stopped || isFinishing()) { result.complete(false); return; }
            StringBuilder text=new StringBuilder("Устройство: ").append(sender).append("\nИмя отправителя не подтверждено.\n\nФайлы:\n");
            int count=0;
            for(String name:files.keySet()) { if (++count>8) { text.append("… всего ").append(files.size()); break; } text.append(name).append('\n'); }
            shown[0]=new AlertDialog.Builder(this).setTitle("Принять передачу?").setMessage(text.toString())
                .setPositiveButton("Принять",(dialog,which) -> result.complete(true))
                .setNegativeButton("Отклонить",(dialog,which) -> result.complete(false))
                .setOnCancelListener(dialog -> result.complete(false)).create();
            shown[0].show(); shown[0].getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
        });
        try { return result.get(40,TimeUnit.SECONDS); }
        catch (TimeoutException error) { return false; }
        finally { result.cancel(true); runOnUiThread(() -> { if(shown[0]!=null) shown[0].dismiss(); }); }
    }

    private void advertise(int port) {
        if(stopped) return;
        NsdServiceInfo info=new NsdServiceInfo();
        String id=UUID.randomUUID().toString().replace("-","").substring(0,12);
        info.setServiceName(id); info.setServiceType("_airdrop._tcp."); info.setPort(port); info.setAttribute("flags","136");
        registration=new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo registered) {
                if(stopped) { try { nsd.unregisterService(this); } catch(Exception ignored) {} return; }
                update(browserStatus());
            }
            public void onRegistrationFailed(NsdServiceInfo service,int error) { update(browserStatus()); }
            public void onServiceUnregistered(NsdServiceInfo service) { Log.i(TAG,"Bonjour stopped"); }
            public void onUnregistrationFailed(NsdServiceInfo service,int error) { Log.i(TAG,"Bonjour stop error "+error); }
        };
        nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
    }

    @Override protected void onDestroy() {
        synchronized(this) { stopped=true; if(receiver!=null) receiver.close(); if(browser!=null) browser.close(); }
        if(registration!=null) try { nsd.unregisterService(registration); } catch(Exception ignored) {}
        super.onDestroy();
    }
}
