package app.quietread;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class Library {
    static final long MAX_FILE=512L*1024*1024, MAX_LIBRARY=1536L*1024*1024;
    static final Set<String> SUPPORTED=new HashSet<>(Arrays.asList((
        "pdf doc docx docm dot dotx dotm rtf odt ott fodt sxw stw " +
        "xls xlsx xlsm xlt xltx xltm xlsb ods ots fods sxc stc csv tsv " +
        "ppt pptx pptm pot potx potm pps ppsx ppsm odp otp fodp sxi sti " +
        "odg otg fodg sxd std txt html htm xhtml svg").split(" ")));
    final Context context;
    final android.content.SharedPreferences prefs;
    final File directory;
    static final class Item {
        String uri,name,path,draftPath; long size,time; float x,y,zoom; int part;
        JSONArray bookmarks=new JSONArray();
        JSONObject json() throws JSONException {
            return new JSONObject().put("uri",uri).put("name",name).put("path",path).put("size",size).put("time",time)
                .put("x",x).put("y",y).put("zoom",zoom).put("part",part).put("bookmarks",bookmarks).put("draftPath",draftPath);
        }
    }
    Library(Context c) { context=c.getApplicationContext(); prefs=context.getSharedPreferences("library",0); directory=new File(context.getFilesDir(),"documents"); directory.mkdirs(); }
    synchronized List<Item> all() {
        ArrayList<Item> result=new ArrayList<>();
        try {
            JSONArray a=new JSONArray(prefs.getString("items","[]"));
            for(int i=0;i<a.length();i++) {
                JSONObject o=a.getJSONObject(i); Item item=new Item();
                item.uri=o.getString("uri"); item.name=o.getString("name"); item.path=o.getString("path");
                item.draftPath=o.optString("draftPath",null);
                item.size=o.optLong("size"); item.time=o.optLong("time");
                item.x=(float)o.optDouble("x"); item.y=(float)o.optDouble("y"); item.zoom=(float)o.optDouble("zoom"); item.part=o.optInt("part");
                item.bookmarks=o.optJSONArray("bookmarks"); if(item.bookmarks==null) item.bookmarks=new JSONArray();
                result.add(item);
            }
        } catch(JSONException ignored) { }
        result.sort((a,b)->Long.compare(b.time,a.time)); return result;
    }
    synchronized void save(Item item) {
        List<Item> all=all(); all.removeIf(i->i.uri.equals(item.uri)); all.add(0,item);
        write(all);
    }
    private void write(List<Item> items) {
        JSONArray a=new JSONArray(); try { for(Item i:items) a.put(i.json()); } catch(JSONException ignored) { }
        prefs.edit().putString("items",a.toString()).apply();
    }
    synchronized void remove(Item item) {
        List<Item> items=all(); items.removeIf(i->i.uri.equals(item.uri)); write(items);
        // Only remove the imported private copy, never the source document.
        File copy=new File(item.path);
        try { if(copy.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) copy.delete(); } catch(IOException ignored) { }
        removeDraft(item);
    }
    synchronized void removeDraft(Item item) {
        if(item.draftPath!=null)try{File f=new File(item.draftPath);if(f.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))f.delete();}catch(IOException ignored){}
        item.draftPath=null;
    }
    Item importUri(Uri uri) throws Exception {
        String name=null; long size=-1;
        if("content".equals(uri.getScheme())) {
            try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
                if(c!=null && c.moveToFirst()) { name=c.getString(0); if(!c.isNull(1)) size=c.getLong(1); }
            }
        }
        if(name==null) name=uri.getLastPathSegment();
        if(name==null) name="Документ";
        String ext=extension(name);
        if(!SUPPORTED.contains(ext)) throw new IOException("Расширение ."+ext+" пока не поддерживается. Выберите документ Office, LibreOffice, PDF, RTF, CSV или TXT.");
        if(size>MAX_FILE) throw new IOException("Файл больше 512 МБ. Откройте меньший документ.");
        String id=hex(MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8))).substring(0,32);
        File dest=new File(directory,id+"."+ext), tmp=new File(directory,id+".partial");
        long stored=0; File[] files=directory.listFiles(); if(files!=null) for(File f:files) if(!f.equals(dest)&&!f.equals(tmp)) stored+=f.length();
        if(stored+Math.max(0,size)>MAX_LIBRARY) throw new IOException("Библиотека занимает 1,5 ГБ. Удалите ненужные копии из списка долгим нажатием.");
        long count=0;
        try(InputStream in=context.getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(tmp)) {
            if(in==null) throw new IOException("Файл недоступен");
            byte[] buf=new byte[65536]; int n;
            while((n=in.read(buf))!=-1) {
                count+=n;
                if(count>MAX_FILE || count+stored>MAX_LIBRARY) throw new IOException("Недостаточно места в библиотеке или файл больше 512 МБ");
                out.write(buf,0,n);
            }
        } catch(Exception ex) { tmp.delete(); throw ex; }
        if(count==0) { tmp.delete(); throw new IOException("Файл пуст"); }
        java.nio.file.Files.move(tmp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Item item=null;
        for(Item old:all()) if(old.uri.equals(uri.toString())) { item=old; break; }
        if(item==null) item=new Item();
        item.uri=uri.toString(); item.name=name; item.path=dest.getPath(); item.size=count; item.time=System.currentTimeMillis(); save(item);
        return item;
    }
    static String extension(String name) { int i=name.lastIndexOf('.'); return i<0?"":name.substring(i+1).toLowerCase(Locale.ROOT); }
    static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    static String size(long bytes) { return bytes<1024*1024?String.format(Locale.getDefault(),"%.0f КБ",bytes/1024.0):String.format(Locale.getDefault(),"%.1f МБ",bytes/1048576.0); }
    static int family(String name) {
        String e=extension(name);
        if(e.equals("pdf")) return 1;
        if(e.matches("xls.*|xlt.*|ods|ots|fods|sxc|stc|csv|tsv")) return 3;
        if(e.matches("ppt.*|pot.*|pps.*|odp|otp|fodp|sxi|sti")) return 4;
        return 2;
    }
}
