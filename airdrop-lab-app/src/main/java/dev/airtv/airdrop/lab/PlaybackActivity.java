package dev.airtv.airdrop.lab;

import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Local Android media decoder; no network URLs or exported entry points. */
public final class PlaybackActivity extends Activity {
    private VideoView video;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try {
            String path=getIntent().getStringExtra("path"); if(path==null) throw new IOException();
            File file=new File(path).getCanonicalFile(); File root=new File(getFilesDir(),"received").getCanonicalFile();
            if(!file.isFile() || !file.getPath().startsWith(root.getPath()+File.separator) || file.getPath().contains(File.separator+".web-partial-")) throw new IOException();
            FrameLayout frame=new FrameLayout(this); frame.setBackgroundColor(android.graphics.Color.BLACK);
            video=new VideoView(this); FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER); frame.addView(video,params); setContentView(frame);
            MediaController controls=new MediaController(this); controls.setAnchorView(video); video.setMediaController(controls);
            video.setOnPreparedListener(player -> video.start());
            video.setOnErrorListener((player,what,extra)->{
                new AlertDialog.Builder(this).setTitle("Не удалось воспроизвести").setMessage("Системный плеер телевизора не поддерживает этот файл или кодек.").setPositiveButton("Вернуться",(dialog,which)->finish()).setOnCancelListener(dialog->finish()).show(); return true;
            });
            video.setVideoPath(file.getAbsolutePath()); video.requestFocus();
        } catch(IOException error) { finish(); }
    }
    @Override protected void onPause() { if(video!=null) video.pause(); super.onPause(); }
    @Override protected void onDestroy() { if(video!=null) video.stopPlayback(); super.onDestroy(); }
}
