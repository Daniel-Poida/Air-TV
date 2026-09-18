package io.github.jqssun.airplay.files;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owned files only. Downloads are published after the complete batch is received. */
final class FileStore {
    private static FileStore singleton;
    static synchronized FileStore get(Context context) throws IOException { if(singleton==null) singleton=new FileStore(context);return singleton; }
    final Context app;
    final File root;
    private final File index;
    private final File journals;
    static final class Entry {
        final String name,id,location;
        final long size,time;
        Entry(String name,String id,String location,long size,long time) { this.name=name;this.id=id;this.location=location;this.size=size;this.time=time; }
        JSONObject json() throws JSONException { return new JSONObject().put("name",name).put("id",id).put("location",location).put("size",size).put("time",time); }
    }
    FileStore(Context context) throws IOException {
        app=context.getApplicationContext();root=new File(app.getFilesDir(),"received");
        index=new File(app.getFilesDir(),"received-downloads.json");journals=new File(app.getFilesDir(),"incoming-downloads");
        if(!root.isDirectory()&&!root.mkdirs()) throw new IOException("Storage unavailable");
        if(!journals.isDirectory()&&!journals.mkdirs()) throw new IOException("Storage unavailable");
        Set<String> completed=new HashSet<>();try { JSONArray saved=read(index);for(int i=0;i<saved.length();i++)completed.add(saved.getJSONObject(i).getString("id")); } catch(JSONException error) { throw new IOException("Invalid download index",error); }
        File[] old=journals.listFiles(); if(old!=null) for(File journal:old) try {
            JSONArray array=read(journal);for(int i=0;i<array.length();i++) { String id=array.getJSONObject(i).getString("id");if(!completed.contains(id))deleteOwned(id); } journal.delete();
        } catch(Exception error) { android.util.Log.w("AirTVFiles","Interrupted download cleanup failed",error); }
    }
    long available(boolean downloads) {
        File path=downloads?Environment.getExternalStorageDirectory():root;
        return Math.max(0,path.getUsableSpace()-16L*1024*1024);
    }
    Session begin(List<String> names,boolean downloads) throws IOException { return new Session(names,downloads); }
    final class Session {
        private final String uuid=UUID.randomUUID().toString();
        private final List<String> names;
        private final boolean downloads;
        private final File staging,finalDirectory,journal;
        private final List<Entry> entries=new ArrayList<>();
        private boolean committed;
        Session(List<String> names,boolean downloads) throws IOException {
            this.names=new ArrayList<>(names);this.downloads=downloads;
            File parent=root;
            if(downloads&&Build.VERSION.SDK_INT<29) {
                if(androidx.core.content.ContextCompat.checkSelfPermission(app,android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw new IOException("Downloads permission required");
                parent=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"AirTV");
                if(!parent.isDirectory()&&!parent.mkdirs()) throw new IOException("Downloads unavailable");
            }
            staging=downloads&&Build.VERSION.SDK_INT>=29?null:new File(parent,".web-partial-"+uuid);
            finalDirectory=staging==null?null:new File(parent,"web-"+uuid);
            journal=new File(journals,uuid+".json");
            if(staging!=null&&!staging.mkdir()) throw new IOException("Cannot create transfer");
        }
        OutputStream open(int number,long size) throws IOException {
            String name=names.get(number);
            if(downloads&&Build.VERSION.SDK_INT>=29) {
                ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);
                values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/AirTV");
                values.put(MediaStore.Downloads.MIME_TYPE,mime(name));values.put(MediaStore.Downloads.IS_PENDING,1);
                Uri uri=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                if(uri==null) throw new IOException("Cannot create download");
                entries.add(new Entry(name,uri.toString(),"Downloads",size,System.currentTimeMillis()));
                try { saveJournal(); OutputStream output=app.getContentResolver().openOutputStream(uri,"w");if(output==null) throw new IOException("Cannot open download");return new BufferedOutputStream(output); }
                catch(Exception error) { throw new IOException("Cannot prepare download",error); }
            }
            File file=new File(staging,name);
            if(!file.getCanonicalPath().startsWith(staging.getCanonicalPath()+File.separator)||!file.createNewFile()) throw new IOException("Invalid target");
            if(downloads) { entries.add(new Entry(name,Uri.fromFile(file).toString(),"Downloads",size,System.currentTimeMillis()));saveJournal(); }
            return new BufferedOutputStream(new FileOutputStream(file));
        }
        JSONArray checksums() throws IOException {
            JSONArray result=new JSONArray();
            try {
                for(int i=0;i<names.size();i++) {
                    String name=names.get(i);java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                    InputStream input=staging!=null?new FileInputStream(new File(staging,name)):app.getContentResolver().openInputStream(Uri.parse(entries.get(i).id));
                    if(input==null)throw new IOException("Cannot verify transfer");
                    try(InputStream stream=input) {byte[] buffer=new byte[65536];int count;while((count=stream.read(buffer))!=-1)digest.update(buffer,0,count);}
                    StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",value&255));
                    result.put(new JSONObject().put("name",name).put("sha256",hex.toString()));
                }
                return result;
            }catch(Exception error){throw new IOException("Cannot verify transfer",error);}
        }
        void finish() throws IOException {
            try {
                if(staging!=null&&!staging.renameTo(finalDirectory)) throw new IOException("Cannot commit transfer");
                if(downloads) {
                    List<Entry> published=new ArrayList<>();
                    for(Entry entry:entries) {
                        if(Build.VERSION.SDK_INT>=29) {
                            ContentValues values=new ContentValues();values.put(MediaStore.Downloads.IS_PENDING,0);
                            if(app.getContentResolver().update(Uri.parse(entry.id),values,null,null)!=1) throw new IOException("Cannot publish download");published.add(entry);
                        } else {
                            published.add(new Entry(entry.name,Uri.fromFile(new File(finalDirectory,entry.name)).toString(),entry.location,entry.size,entry.time));
                        }
                    }
                    synchronized(FileStore.this) { JSONArray array=read(index);for(Entry entry:published) array.put(entry.json());write(index,array); }
                }
                committed=true;journal.delete();
            } catch(Exception error) { cancel();throw new IOException("Cannot finish transfer",error); }
        }
        void cancel() {
            if(committed) return;
            for(Entry entry:entries) try { deleteOwned(entry.id); } catch(Exception ignored) {}
            remove(staging);remove(finalDirectory);journal.delete();
        }
        private void saveJournal() throws IOException { try { JSONArray array=new JSONArray();for(Entry entry:entries) { array.put(entry.json());if(downloads&&Build.VERSION.SDK_INT<29)array.put(new Entry(entry.name,Uri.fromFile(new File(finalDirectory,entry.name)).toString(),entry.location,entry.size,entry.time).json()); }write(journal,array); } catch(JSONException error) { throw new IOException(error); } }
    }
    synchronized List<Entry> list() {
        List<Entry> result=new ArrayList<>();Deque<File> directories=new ArrayDeque<>();directories.add(root);
        while(!directories.isEmpty()) {
            File[] files=directories.removeFirst().listFiles();if(files==null) continue;
            for(File file:files) { if(file.isDirectory()) { if(!file.getName().startsWith(".") && !file.getName().endsWith(".partial")) directories.add(file); } else if(file.isFile()) result.add(new Entry(file.getName(),Uri.fromFile(file).toString(),"Память Air TV",file.length(),file.lastModified())); }
        }
        try { JSONArray array=read(index);for(int i=0;i<array.length();i++) { JSONObject item=array.getJSONObject(i);result.add(new Entry(item.getString("name"),item.getString("id"),item.getString("location"),item.getLong("size"),item.getLong("time"))); } }
        catch(Exception error) { android.util.Log.w("AirTVFiles","Downloads index unavailable",error); }
        result.sort((a,b)->Long.compare(b.time,a.time));return result;
    }
    synchronized Entry find(String id) { for(Entry entry:list()) if(entry.id.equals(id)) return entry;return null; }
    InputStream open(Entry entry) throws IOException {
        if(find(entry.id)==null) throw new IOException("Unknown file");
        Uri uri=Uri.parse(entry.id); if("file".equals(uri.getScheme())) return new FileInputStream(new File(uri.getPath()));
        InputStream input=app.getContentResolver().openInputStream(uri);if(input==null) throw new IOException("Cannot read file");return input;
    }
    synchronized void delete(Entry entry) throws IOException {
        if(find(entry.id)==null) throw new IOException("Unknown file");
        try {
            deleteOwned(entry.id);JSONArray old=read(index),updated=new JSONArray();
            for(int i=0;i<old.length();i++) if(!old.getJSONObject(i).getString("id").equals(entry.id)) updated.put(old.getJSONObject(i));write(index,updated);
        } catch(Exception error) { throw new IOException("Cannot delete file",error); }
    }
    private void deleteOwned(String id) throws IOException {
        Uri uri=Uri.parse(id);
        if("content".equals(uri.getScheme())&&"media".equals(uri.getAuthority())&&uri.getPath()!=null&&uri.getPath().matches("/(external|external_primary)/downloads/[0-9]+")) { app.getContentResolver().delete(uri,null,null);return; }
        if(!"file".equals(uri.getScheme())||uri.getPath()==null) throw new IOException("Invalid owned file");
        File file=new File(uri.getPath()).getCanonicalFile();String privatePrefix=root.getCanonicalPath()+File.separator;
        String downloadsPrefix=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"AirTV").getCanonicalPath()+File.separator;
        if(!file.getPath().startsWith(privatePrefix)&&!file.getPath().startsWith(downloadsPrefix)) throw new IOException("Invalid owned file");
        if(file.exists()&&!file.delete()) throw new IOException("Cannot delete file");
    }
    static String mime(String name) { String extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";String type=MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);return type==null?"application/octet-stream":type; }
    private static JSONArray read(File file) throws IOException,JSONException {
        if(!file.exists()) return new JSONArray();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=new FileInputStream(file)) { byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1) bytes.write(buffer,0,count); }
        return new JSONArray(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
    }
    private static void write(File file,JSONArray data) throws IOException {
        android.util.AtomicFile atomic=new android.util.AtomicFile(file);FileOutputStream output=null;
        try { output=atomic.startWrite();output.write(data.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(output); }
        catch(IOException error) { if(output!=null) atomic.failWrite(output);throw error; }
    }
    private static void remove(File file) { if(file==null)return;File[] files=file.listFiles();if(files!=null)for(File child:files)remove(child);file.delete(); }
}
