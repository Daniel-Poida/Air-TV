package io.github.jqssun.airplay.files;

import android.app.*;
import android.content.*;
import android.net.nsd.*;
import android.os.*;
import android.security.keystore.*;
import android.util.Log;
import io.github.jqssun.airplay.Prefs;
import dev.airtv.airdrop.AirDropReceiver;
import java.io.*;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

/** File reception shares the Air TV foreground service and its saved PIN preference. */
public final class FilesReceiver implements Closeable {
    private static final String TAG="AirTVFiles", ALIAS="airtv-files-tls-v2", CHANNEL="airtv-files";
    private static volatile FilesReceiver instance;
    private static WeakReference<FilesActivity> activity=new WeakReference<>(null);
    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanged;
    private volatile BrowserReceiver browser;
    private volatile FileStore storage;
    private volatile AirDropReceiver airDrop;
    private volatile boolean closed;
    private volatile String message="Запуск приёма файлов…";
    private volatile Pending pending;
    private volatile boolean transferring;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    public static final class Pending {
        final String sender;
        final Map<String,Boolean> files;
        final CompletableFuture<Boolean> result=new CompletableFuture<>();
        Pending(String sender,Map<String,Boolean> files) { this.sender=sender; this.files=new LinkedHashMap<>(files); }
    }
    private FilesReceiver(Context context) {
        app=context.getApplicationContext(); prefs=app.getSharedPreferences(Prefs.NAME,Context.MODE_PRIVATE);
        preferenceChanged=(p,key)->{ if(Prefs.REQUIRE_PIN.equals(key)) { BrowserReceiver web=browser; if(web!=null) web.setRequirePin(p.getBoolean(Prefs.REQUIRE_PIN,Prefs.DEF_REQUIRE_PIN)); } notifyUi(); };
        prefs.registerOnSharedPreferenceChangeListener(preferenceChanged);
    }
    public static synchronized void start(Context context) {
        if(instance!=null) return;
        FilesReceiver receiver=new FilesReceiver(context); instance=receiver;
        new Thread(receiver::initialize,"AirTV-files-start").start(); receiver.notifyUi();
    }
    public static void stop() { FilesReceiver old; synchronized(FilesReceiver.class) { old=instance; instance=null; } if(old!=null) old.close(); }
    public static boolean isBusy() { FilesReceiver receiver=instance;return receiver!=null&&(receiver.pending!=null || (receiver.browser!=null&&receiver.browser.isBusy())); }
    public static FilesReceiver current() { return instance; }
    static synchronized void attach(FilesActivity screen) { activity=new WeakReference<>(screen); }
    static synchronized void detach(FilesActivity screen) { if(activity.get()==screen) activity.clear(); }
    public static synchronized boolean isUiVisible() { return activity.get()!=null; }
    private void notifyUi() { main.post(()->{ FilesActivity screen; synchronized(FilesReceiver.class) { screen=activity.get(); } if(screen!=null) screen.refresh(); }); }
    Pending pending() { return pending; }
    TransferProgress progress() { BrowserReceiver web=browser;return web==null?null:web.progress(); }
    void cancelTransfer() { BrowserReceiver web=browser;if(web!=null) { message="Передача отменена.";web.cancelTransfer();notifyUi(); } }
    FileStore store() { return storage; }
    private void initialize() {
        try {
            BrowserReceiver web=new BrowserReceiver(storage=FileStore.get(app),()->prefs.getBoolean(Prefs.SAVE_DOWNLOADS,false),this::approve,this::received,this::progressChanged);
            synchronized(this) { if(closed) { web.close(); return; } web.setRequirePin(prefs.getBoolean(Prefs.REQUIRE_PIN,Prefs.DEF_REQUIRE_PIN)); browser=web; web.start(); }
            message=""; notifyUi();
        } catch(Exception error) { message="Не удалось запустить браузерный приёмник. Порт 8786 может быть занят."; Log.e(TAG,"Browser startup failed",error); notifyUi(); }
        try {
            AirDropReceiver receiver=new AirDropReceiver(context(),InetAddress.getByName("::"),8785,
                prefs.getString(Prefs.SERVER_NAME,Prefs.DEF_SERVER_NAME),new File(app.getFilesDir(),"received"),this::approve,this::received);
            synchronized(this) { if(closed) { receiver.close(); return; } airDrop=receiver; receiver.start(); }
            main.post(()->advertise(receiver.port()));
        } catch(Exception error) { Log.w(TAG,"Experimental AirDrop startup failed",error); }
    }
    private void progressChanged(TransferProgress state) {
        if(state!=null) {transferring=true;message="";}
        else if(transferring) {transferring=false;if(message.isEmpty())message="Передача прервалась. Отправь файлы ещё раз.";}
        notifyUi();
    }
    private void received(File directory) { message="Передача завершена. Выбери файл ниже."; notifyUi(); }
    private boolean approve(String sender,Map<String,Boolean> files) throws Exception {
        Pending request=new Pending(sender,files);
        synchronized(this) { if(closed || pending!=null) return false; pending=request; }
        notifyUi();
        main.post(()->{
            if(closed || pending!=request) return;
            Intent intent=new Intent(app,FilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            NotificationManager notifications=(NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE);
            if(Build.VERSION.SDK_INT>=26) notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"Передача файлов",NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open=PendingIntent.getActivity(app,42,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(app,CHANNEL):new Notification.Builder(app);
            try { notifications.notify(42,builder.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("Air TV · Принять файлы?").setContentText("Открой Air TV, чтобы подтвердить передачу").setContentIntent(open).setAutoCancel(true).build()); } catch(SecurityException ignored) {}
            if(prefs.getBoolean(Prefs.LAUNCH_ON_CONNECT,Prefs.DEF_LAUNCH_ON_CONNECT)) try { app.startActivity(intent); } catch(Exception error) { Log.w(TAG,"Open files screen failed",error); }
        });
        try { return request.result.get(40,TimeUnit.SECONDS); }
        catch(TimeoutException error) { return false; }
        finally {
            synchronized(this) { if(pending==request) pending=null; }
            request.result.cancel(true); ((NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(42); notifyUi();
        }
    }
    String url() { return "http://"+address()+":8786/"; }
    private String address() {
        try {
            Enumeration<NetworkInterface> faces=NetworkInterface.getNetworkInterfaces();
            while(faces.hasMoreElements()) {
                NetworkInterface face=faces.nextElement();if(!face.isUp()||face.isLoopback())continue;
                Enumeration<InetAddress> addresses=face.getInetAddresses();
                while(addresses.hasMoreElements()) { InetAddress ip=addresses.nextElement();if(ip instanceof Inet4Address&&ip.isSiteLocalAddress())return ip.getHostAddress(); }
            }
        } catch(Exception ignored) {}
        return "IP телевизора";
    }
    String status() {
        BrowserReceiver web=browser;if(web==null)return message;
        return url()+"\n"+(web.requiresPin()?"PIN: "+web.token:"PIN отключён")+"\nСохранять: "+(prefs.getBoolean(Prefs.SAVE_DOWNLOADS,false)?"Downloads":"Память Air TV")+(message.isEmpty()?"":"\n"+message);
    }
    private synchronized void advertise(int port) {
        if(closed) return;
        nsd=(NsdManager)app.getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo info=new NsdServiceInfo(); info.setServiceName(UUID.randomUUID().toString().replace("-","").substring(0,12)); info.setServiceType("_airdrop._tcp."); info.setPort(port); info.setAttribute("flags","136");
        registration=new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo info) { if(closed) try { nsd.unregisterService(this); } catch(Exception ignored) {} }
            public void onRegistrationFailed(NsdServiceInfo info,int error) { Log.w(TAG,"AirDrop NSD registration: "+error); }
            public void onServiceUnregistered(NsdServiceInfo info) {}
            public void onUnregistrationFailed(NsdServiceInfo info,int error) {}
        };
        nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
    }
    public void close() {
        synchronized(this) { closed=true; if(pending!=null) pending.result.complete(false); if(browser!=null) browser.close(); if(airDrop!=null) airDrop.close(); }
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChanged);
        if(registration!=null) try { nsd.unregisterService(registration); } catch(Exception ignored) {}
        ((NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(42); notifyUi();
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
        
        
        return context;
    }


}
