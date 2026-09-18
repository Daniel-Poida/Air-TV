package io.github.jqssun.airplay.files;

/** Standalone deterministic test: javac TransferProgress.java TransferProgressTest.java; java -ea ... */
public final class TransferProgressTest {
    public static void main(String[] args) {
        TransferProgress.Meter meter=new TransferProgress.Meter(2000000,0);
        TransferProgress initial=meter.sample("first","Принимаем",1,2,0,0);
        assert initial.fraction()==0 && initial.remainingSeconds()==-1;
        TransferProgress half=meter.sample("first","Принимаем",1,2,1000000,1000000000L);
        assert half.fraction()==.5f && half.bytesPerSecond==1000000 && half.remainingSeconds()==1;
        TransferProgress next=meter.sample("second","Принимаем",2,2,1500000,2000000000L);
        assert next.bytesPerSecond==850000 && next.remainingSeconds()==1 && next.fraction()==.75f;
        TransferProgress empty=new TransferProgress("empty","Принимаем",1,1,0,0,0);
        assert empty.fraction()==1;
        TransferProgress huge=new TransferProgress("huge","Принимаем",1,1,Long.MAX_VALUE/2,Long.MAX_VALUE,1);
        assert Math.abs(huge.fraction()-.5f)<.0001f;
        System.out.println("PASS progress: whole batch, rate smoothing, ETA, empty and long file sizes");
    }
}
