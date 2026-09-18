package dev.airtv.tests;
import android.app.Instrumentation;
import android.os.Bundle;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;

/** Test-only signed instrumentation. No receiver debug endpoints are shipped. Run on an idle test TV. */
public final class RecoveryTest extends Instrumentation {
    private boolean transfer;
    public void onCreate(Bundle args) {super.onCreate(args);transfer=args!=null && "transfer".equals(args.getString("mode"));start();}
    public void onStart() {new Thread(()->{Bundle result=new Bundle();try {check();result.putString("stream","PASS receiver recovery, disconnect cleanup, generation guard / concurrent transfer");finish(0,result);}catch(Throwable e){result.putString("stream","FAIL "+e);finish(1,result);}},"AirTV-recovery-test").start();}
    private Object service;
    private Class<?> type;
    private Object field(String name) throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(service);}
    private void call(String name,Class<?>[] parameters,Object... values) {runOnMainSync(()->{try{type.getMethod(name,parameters).invoke(service,values);}catch(Exception e){throw new RuntimeException(e);}});}
    private void assertThat(boolean value,String message) {if(!value)throw new AssertionError(message);}
    private Object state(String name) throws Exception {
        Object flow=field(name);
        Class<?> c=flow.getClass();
        for(Method m:c.getMethods()) if(m.getName().equals("getValue") && m.getParameterCount()==0) {m.setAccessible(true);return m.invoke(flow);}
        throw new AssertionError("No getValue for "+name);
    }
    private boolean ownTransfer(boolean staging) {
        java.io.File root=new java.io.File(getTargetContext().getFilesDir(),"received");
        java.io.File[] directories=root.listFiles();if(directories==null)return false;
        for(java.io.File dir:directories) if(dir.getName().startsWith(staging?".web-partial-":"web-") &&
            new java.io.File(dir,"AirTV-progress-test-recovery.bin").isFile())return true;
        return false;
    }
    private void checkTransfer() throws Exception {
        getTargetContext().startActivity(new android.content.Intent().setClassName("dev.airtv.receiver","io.github.jqssun.airplay.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        Bundle ready=new Bundle();ready.putString("stream","READY for scoped synthetic upload");sendStatus(0,ready);
        for(int i=0;i<200&&!ownTransfer(true);i++)Thread.sleep(250);
        assertThat(ownTransfer(true),"Test upload not started");
        call("onConnectionReset",new Class<?>[]{int.class},1);
        Thread.sleep(4500);
        assertThat(field("recovery")==null && (Long)field("nativeHandle")!=0,"Recovery not completed during upload");
        for(int i=0;i<240&&!ownTransfer(false);i++)Thread.sleep(250);
        assertThat(ownTransfer(false),"Concurrent upload did not commit");
        Thread.sleep(2000);
    }
    private void check() throws Exception {
        type=Class.forName("io.github.jqssun.airplay.service.AirPlayService");
        Field live=type.getDeclaredField("liveService");live.setAccessible(true);
        android.content.Intent receiver=new android.content.Intent().setClassName("dev.airtv.receiver",type.getName()).setAction("io.github.jqssun.airplay.START_SERVER");
        if(android.os.Build.VERSION.SDK_INT>=26)getTargetContext().startForegroundService(receiver);else getTargetContext().startService(receiver);
        for(int i=0;i<40;i++) {service=((WeakReference<?>)live.get(null)).get();if(service!=null && (Long)field("nativeHandle")!=0)break;Thread.sleep(250);}
        assertThat(service!=null,"Receiver not started");
        assertThat(((Number)state("_connectionCount")).intValue()==0,"Test requires idle receiver");
        assertThat((Long)field("nativeHandle")!=0,"No running native engine");
        if(transfer) {checkTransfer();return;}
        call("onConnectionInit",new Class<?>[]{});
        call("onVideoSize",new Class<?>[]{float.class,float.class,float.class,float.class},1920f,1080f,1920f,1080f);
        assertThat((Boolean)state("_mirroringActive"),"Mirror state not active");
        call("onConnectionDestroy",new Class<?>[]{});
        assertThat(!(Boolean)state("_mirroringActive"),"Disconnect left stale mirror state");
        call("onConnectionReset",new Class<?>[]{int.class},1);
        Thread.sleep(300);assertThat(field("recovery")!=null,"Reset not scheduled");
        Thread.sleep(4500);assertThat(field("recovery")==null,"Reset not completed");
        assertThat((Long)field("nativeHandle")!=0,"Engine not recovered");
        assertThat(state("_connectionNotice").toString().contains("Mac"),"Reconnect instructions missing");
        call("onConnectionReset",new Class<?>[]{int.class},1);Thread.sleep(300);
        call("onMirrorRunning",new Class<?>[]{boolean.class},true);Thread.sleep(250);
        assertThat(field("recovery")==null,"New mirror session did not cancel old reset");
        assertThat(field("wifiLock")!=null,"No active Wi-Fi streaming lock");
        call("onMirrorRunning",new Class<?>[]{boolean.class},false);Thread.sleep(250);
        assertThat(field("wifiLock")==null,"Wi-Fi streaming lock leaked");
        // Stop fake audio mode left by mirror teardown, without stopping the receiver.
        call("onConnectionDestroy",new Class<?>[]{});
    }
}
