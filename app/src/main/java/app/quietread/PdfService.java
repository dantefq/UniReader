package app.quietread;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.*;
import com.artifex.mupdf.fitz.*;
import org.json.*;
import java.io.File;
import java.util.concurrent.*;

/** Dedicated native PDF process. LibreOffice and MuPDF must not share signal handlers. */
public final class PdfService extends Service {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"UniReader-PDF"));
    private NativePdfEngine engine;
    private final Binder binder=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
        if(code!=FIRST_CALL_TRANSACTION)return false;
        String action=data.readString(),request=data.readString();
        try{Result result=worker.submit(()->dispatch(action,new JSONObject(request))).get();reply.writeNoException();reply.writeString(result.json.toString());
            reply.writeInt(result.bitmap==null?0:1);if(result.bitmap!=null){try{result.bitmap.writeToParcel(reply,Parcelable.PARCELABLE_WRITE_RETURN_VALUE);}finally{result.bitmap.recycle();}}return true;
        }catch(Exception ex){Throwable cause=ex instanceof ExecutionException?ex.getCause():ex;reply.writeException(new IllegalStateException(cause.getMessage()));return true;}
    }};
    static final class Result{JSONObject json=new JSONObject();Bitmap bitmap;}
    private Result dispatch(String action,JSONObject args)throws Exception {
        Result result=new Result();
        switch(action){
            case "open":if(engine!=null)engine.close();engine=new NativePdfEngine(this,new File(args.getString("path")));result.json.put("count",engine.count());break;
            case "part":engine.setPart(args.getInt("part"));result.json.put("bounds",box(engine.bounds));break;
            case "render":result.bitmap=Bitmap.createBitmap(args.getInt("pw"),args.getInt("ph"),Bitmap.Config.ARGB_8888);result.bitmap.eraseColor(-1);engine.render(result.bitmap,args.getInt("x"),args.getInt("y"),args.getInt("w"),args.getInt("h"));break;
            case "select":result.json.put("line",line(engine.select((float)args.getDouble("x"),(float)args.getDouble("y"))));break;
            case "insert":result.json.put("line",line(engine.insertion((float)args.getDouble("x"),(float)args.getDouble("y"))));break;
            case "replace":engine.replace(line(args.getJSONObject("line")),args.getString("text"),(float)args.getDouble("size"),args.getBoolean("remove"));break;
            case "highlight":engine.highlight(line(args.getJSONObject("line")));break;
            case "undo":engine.undo();break;
            case "redo":engine.redo();break;
            case "save":engine.save(new File(args.getString("path")));break;
            case "search":JSONArray found=new JSONArray();for(Quad[] hit:engine.search(args.getString("query")))for(Quad quad:hit)found.put(box(quad.toRect()));result.json.put("matches",found);break;
            case "close":if(engine!=null){engine.close();engine=null;}break;
            default:throw new IllegalArgumentException("Неизвестная операция PDF");
        }return result;
    }
    private static JSONArray box(Rect r)throws JSONException{return new JSONArray().put(r.x0).put(r.y0).put(r.x1).put(r.y1);}
    private static Rect box(JSONArray a)throws JSONException{return new Rect((float)a.getDouble(0),(float)a.getDouble(1),(float)a.getDouble(2),(float)a.getDouble(3));}
    private static JSONObject line(NativePdfEngine.Line line)throws JSONException{if(line==null)return null;return new JSONObject().put("box",box(line.box)).put("x",line.origin.x).put("y",line.origin.y).put("dx",line.dir.x).put("dy",line.dir.y).put("size",line.size).put("color",line.argb).put("text",line.text);}
    private static NativePdfEngine.Line line(JSONObject o)throws JSONException{NativePdfEngine.Line line=new NativePdfEngine.Line();line.box=box(o.getJSONArray("box"));line.origin=new Point((float)o.getDouble("x"),(float)o.getDouble("y"));line.dir=new Point((float)o.getDouble("dx"),(float)o.getDouble("dy"));line.size=(float)o.getDouble("size");line.argb=o.getInt("color");line.text=o.getString("text");return line;}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public void onDestroy(){worker.execute(()->{if(engine!=null){engine.close();engine=null;}});worker.shutdown();super.onDestroy();}
}
