package app.quietread;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import org.libreoffice.kit.Document;
import org.libreoffice.kit.LibreOfficeKit;
import org.libreoffice.kit.Office;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** All engine calls, including destruction, run on this one serial thread. */
final class ReaderEngine implements AutoCloseable {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UniReader-render"); t.setPriority(Thread.NORM_PRIORITY - 1); return t;
    });
    static Office office;
    Document document;
    PdfEngine pdf;
    Callback callback;
    ParcelFileDescriptor fd;
    int part, type, parts;
    long width, height;
    String[] names;
    List<RectF> pages = new ArrayList<>();
    ByteBuffer tileBuffer;
    int[] blankTile;
    volatile boolean closed;
    interface Callback { void message(int code, String payload); }

    static ReaderEngine open(Activity activity, File file, Callback callback) throws Exception {
        return open(activity,file,callback,false);
    }
    static ReaderEngine openOffice(Activity activity,File file,Callback callback)throws Exception{return open(activity,file,callback,true);}
    private static ReaderEngine open(Activity activity, File file, Callback callback,boolean officePdf) throws Exception {
        ReaderEngine e = new ReaderEngine();
        e.callback=callback;
        try {
            if (!officePdf&&file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                e.pdf = new PdfEngine(activity,file);
                e.parts = e.pdf.count(); e.type = -1;
                e.names = new String[e.parts];
                for (int i=0; i<e.parts; i++) e.names[i] = "Страница " + (i+1);
            } else {
                if (office == null) {
                    unpack(activity, "unpack", new File(activity.getApplicationInfo().dataDir));
                    fontCache(activity);
                    LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts");
                    LibreOfficeKit.putenv("SAL_DISABLE_OPENCL=1");
                    LibreOfficeKit.putenv("SAL_LOG=+WARN");
                    LibreOfficeKit.init(activity);
                    ByteBuffer handle = LibreOfficeKit.getLibreOfficeKitHandle();
                    if (handle == null) throw new IOException("Не удалось запустить движок LibreOffice");
                    office = new Office(handle);
                    // Password callbacks are disabled: encrypted files fail without blocking the worker.
                    office.setOptionalFeatures(0);
                }
                String extension=Library.extension(file.getName());
                File engineFile=(extension.equals("csv")||extension.equals("tsv"))?DelimitedText.prepare(file,activity.getCacheDir()):file;
                e.document = office.documentLoad(engineFile.toURI().toASCIIString());
                if (e.document == null) throw new IOException("Не удалось открыть документ. Возможно, файл повреждён, зашифрован или его формат не поддерживается. " + office.getError());
                e.document.setMessageCallback(callback::message);
                e.document.initializeForRendering();
                e.type = e.document.getDocumentType();
                e.parts = Math.max(1, e.document.getParts());
                e.names = new String[e.parts];
                for (int i=0; i<e.parts; i++) e.names[i] = e.document.getPartName(i);
                if (e.type == Document.DOCTYPE_TEXT) {
                    String rectangles = e.document.getPartPageRectangles();
                    if (rectangles != null) for (String entry : rectangles.split(";")) {
                        try {
                            String[] v = entry.trim().split(",\\s*");
                            if (v.length == 4) {
                                float x=Float.parseFloat(v[0]), y=Float.parseFloat(v[1]);
                                e.pages.add(new RectF(x,y,x+Float.parseFloat(v[2]),y+Float.parseFloat(v[3])));
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
            e.setPart(0);
            return e;
        } catch (Exception | LinkageError ex) { e.close(); throw ex; }
    }

    private static void unpack(Activity activity, String asset, File target) throws IOException {
        File stamp = new File(activity.getFilesDir(), "lo-assets-26.2.6.3");
        if (stamp.exists()) return;
        copyAssets(activity, asset, target);
        // Highest macro security also applies when documents are edited.
        File profile = new File(target, "user"); profile.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(profile,"registrymodifications.xcu"))) {
            out.write(("<?xml version=\"1.0\"?><oor:items xmlns:oor=\"http://openoffice.org/2001/registry\">"+
                "<item oor:path=\"/org.openoffice.Office.Common/Security/Scripting\"><prop oor:name=\"MacroSecurityLevel\" oor:op=\"fuse\"><value>3</value></prop></item></oor:items>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!stamp.createNewFile() && !stamp.exists()) throw new IOException("Не удалось подготовить движок");
    }
    private static void fontCache(Activity activity)throws IOException {
        File directory=new File(activity.getCacheDir(),"fontconfig");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Нет места для кэша шрифтов");
        File config=new File(activity.getApplicationInfo().dataDir,"etc/fonts/fonts.conf");
        String original=new String(java.nio.file.Files.readAllBytes(config.toPath()),java.nio.charset.StandardCharsets.UTF_8);
        String fixed=original.replaceAll("<cachedir>[^<]*</cachedir>","<cachedir>"+directory.getAbsolutePath()+"</cachedir>");
        if(!fixed.equals(original))java.nio.file.Files.write(config.toPath(),fixed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void copyAssets(Activity activity, String path, File target) throws IOException {
        String[] children = activity.getAssets().list(path);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Нет места для движка");
            for (String name : children) copyAssets(activity, path+"/"+name, new File(target,name));
        } else {
            try (InputStream in=activity.getAssets().open(path); OutputStream out=new FileOutputStream(target)) {
                byte[] buf=new byte[65536]; int n; while((n=in.read(buf))!=-1) out.write(buf,0,n);
            }
        }
    }
    void setPart(int value) throws IOException {
        if (closed) return;
        part = Math.max(0, Math.min(value, parts-1));
        if (pdf != null) {
            pdf.setPart(part);width=Math.round(pdf.bounds.width()*20);height=Math.round(pdf.bounds.height()*20);
        } else {
            document.setPart(part); width=document.getDocumentWidth(); height=document.getDocumentHeight();
        }
        if (width<=0 || height<=0) throw new IOException("Движок не вернул размеры документа");
    }
    Bitmap render(int pixels, int x, int y, int w, int h) {
        if (closed) return null;
        if(pdf!=null)return pdf.render(pixels,pixels,x,y,w,h);
        Bitmap bitmap = Bitmap.createBitmap(pixels,pixels,Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        try {
            {
                int bytes = pixels*pixels*4;
                if (tileBuffer==null || tileBuffer.capacity()!=bytes) {
                    tileBuffer=ByteBuffer.allocateDirect(bytes); blankTile=new int[pixels*pixels];
                    Arrays.fill(blankTile,0xffffffff);
                }
                // LOK may leave pixels outside the page untouched. Never reuse previous tile pixels.
                tileBuffer.clear(); tileBuffer.asIntBuffer().put(blankTile);
                document.paintTile(tileBuffer,pixels,pixels,x,y,w,h);
                tileBuffer.rewind(); bitmap.copyPixelsFromBuffer(tileBuffer);
            }
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError ex) { bitmap.recycle(); throw ex; }
    }
    void search(String query, boolean backwards) throws Exception {
        if(pdf!=null){List<RectF> matches=pdf.search(query);if(matches.isEmpty()){callback.message(12,"");return;}RectF r=matches.get(backwards?matches.size()-1:0);
            JSONObject result=new JSONObject().put("part",part).put("rectangles",((r.left-pdf.bounds.left)*20)+","+((r.top-pdf.bounds.top)*20)+","+(r.width()*20)+","+(r.height()*20));callback.message(15,new JSONObject().put("searchResultSelection",new org.json.JSONArray().put(result)).toString());return;}
        JSONObject args=new JSONObject();
        args.put("SearchItem.SearchString", prop("string",query));
        args.put("SearchItem.Backward", prop("boolean",backwards));
        args.put("SearchItem.Command", prop("long",0));
        args.put("SearchItem.SearchFlags", prop("long",0));
        document.postUnoCommand(".uno:ExecuteSearch",args.toString(),false);
    }
    void command(String name,String args) { if(document!=null&&!closed)document.postUnoCommand(name,args==null?"{}":args,false); }
    void click(int x,int y,int count,float zoom) {
        if(document==null||closed)return;
        int twips=Math.max(1,Math.round(384/zoom));document.setClientZoom(384,384,twips,twips);
        document.postMouseEvent(Document.MOUSE_EVENT_BUTTON_DOWN,x,y,count,Document.MOUSE_BUTTON_LEFT,0);
        document.postMouseEvent(Document.MOUSE_EVENT_BUTTON_UP,x,y,count,Document.MOUSE_BUTTON_LEFT,0);
    }
    void paste(String text) throws IOException {
        if(document==null||!document.paste("text/plain;charset=utf-8",text))throw new IOException("Не удалось вставить текст. Поставьте курсор в текст или дважды коснитесь надписи на слайде.");
    }
    void refreshGeometry() throws IOException {
        if(document==null||closed)return;
        width=document.getDocumentWidth();height=document.getDocumentHeight();
        parts=Math.max(1,document.getParts());part=Math.max(0,Math.min(document.getPart(),parts-1));
        names=new String[parts];for(int i=0;i<parts;i++)names[i]=document.getPartName(i);
        ArrayList<RectF> updatedPages=new ArrayList<>();String rectangles=type==0?document.getPartPageRectangles():null;
        if(type==0&&rectangles!=null)for(String entry:rectangles.split(";"))try{
            String[] v=entry.trim().split(",\\s*");if(v.length==4){float x=Float.parseFloat(v[0]),y=Float.parseFloat(v[1]);updatedPages.add(new RectF(x,y,x+Float.parseFloat(v[2]),y+Float.parseFloat(v[3])));}
        }catch(NumberFormatException ignored){}
        pages=updatedPages;
    }
    void export(File target,String extension,String options) throws IOException {
        if(pdf!=null&&!closed){if(!extension.equals("pdf"))throw new IOException("Для PDF доступны PDF и изображения");pdf.save(target);return;}
        if(document==null||closed)throw new IOException("Этот документ нельзя экспортировать офисным фильтром");
        document.saveAs(target.toURI().toASCIIString(),extension,options==null?"":options);
        String error=office.getError();
        if(!target.isFile()||target.length()==0||(error!=null&&!error.trim().isEmpty()))throw new IOException("Экспорт в "+extension.toUpperCase(Locale.ROOT)+" не выполнен. "+(error==null?"":error));
    }
    Bitmap image(int page) throws IOException {
        long x=0,y=0,w=width,h=height;
        if(type==0&&!pages.isEmpty()){RectF r=pages.get(Math.min(page,pages.size()-1));x=(long)r.left;y=(long)r.top;w=(long)r.width();h=(long)r.height();}
        float ratio=Math.min(4096f/Math.max(w,h),.15f);
        ratio=Math.min(ratio,(float)Math.sqrt(8000000d/((double)w*h)));
        int pw=Math.max(1,Math.round(w*ratio)),ph=Math.max(1,Math.round(h*ratio));
        if(pdf!=null)return pdf.render(pw,ph,0,0,(int)width,(int)height);
        Bitmap bitmap=Bitmap.createBitmap(pw,ph,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
        {ByteBuffer buffer=ByteBuffer.allocateDirect(pw*ph*4);int[] white=new int[pw];Arrays.fill(white,-1);java.nio.IntBuffer ints=buffer.asIntBuffer();for(int row=0;row<ph;row++)ints.put(white);document.paintTile(buffer,pw,ph,(int)x,(int)y,(int)w,(int)h);buffer.rewind();bitmap.copyPixelsFromBuffer(buffer);}
        return bitmap;
    }
    private static JSONObject prop(String type,Object value) throws Exception {
        return new JSONObject().put("type",type).put("value",value);
    }
    @Override public void close() {
        closed=true;
        if (document!=null) { document.destroy(); document=null; }
        if (pdf!=null) { pdf.close(); pdf=null; }
        if (fd!=null) try { fd.close(); } catch(IOException ignored) { }
        fd=null; tileBuffer=null; blankTile=null;
    }
}
