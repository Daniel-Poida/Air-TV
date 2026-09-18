package io.github.jqssun.airplay.files;

/** Immutable snapshot; reading progress must never wait on the upload/storage lock. */
public final class TransferProgress {
    public final String name, phase;
    public final int file, files;
    public final long received, total;
    public final double bytesPerSecond;
    public TransferProgress(String name,String phase,int file,int files,long received,long total,double bytesPerSecond) {
        this.name=name;this.phase=phase;this.file=file;this.files=files;
        this.received=received;this.total=total;this.bytesPerSecond=bytesPerSecond;
    }
    public float fraction() { return total==0?1f:(float)Math.min(1d,(double)received/total); }
    public long remainingSeconds() { return bytesPerSecond>0?(long)Math.ceil((total-received)/bytesPerSecond):-1; }
    static final class Meter {
        private final long total,started;
        private long lastTime,lastBytes;
        private double speed;
        Meter(long total,long now) { this.total=total;started=lastTime=now; }
        TransferProgress sample(String name,String phase,int file,int files,long bytes,long now) {
            long elapsed=now-lastTime;
            if(elapsed>=250000000L) {
                double current=(bytes-lastBytes)*1e9/elapsed;
                speed=lastTime==started?current:.7*speed+.3*current;
                lastTime=now;lastBytes=bytes;
            }
            return new TransferProgress(name,phase,file,files,bytes,total,speed);
        }
    }
}
