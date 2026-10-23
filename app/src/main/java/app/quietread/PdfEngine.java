package app.quietread;

import android.content.*;
import android.graphics.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Worker-thread proxy; the UI/Office process never loads MuPDF native libraries. */
final class PdfEngine implements AutoCloseable {
    private final Context context;
    private volatile IBinder binder;
    private final CountDownLatch connected=new CountDownLatch(1);
    private boolean bound;
    private int count;
    RectF bounds;
    static final class Line {
        final JSONObject data;final float size;final String text;
        Line(JSONObject data){this.data=data;size=(float)data.optDouble("size",12);text=data.optString("text");}
    }
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder service){binder=service;connected.countDown();}
        public void onServiceDisconnected(ComponentName name){binder=null;}
        public void onNullBinding(ComponentName name){connected.countDown();}
    };
    PdfEngine(Context context,File source)throws IOException {
        this.context=context.getApplicationContext();
        try{bound=this.context.bindService(new Intent(this.context,PdfService.class),connection,Context.BIND_AUTO_CREATE);
            if(!bound||!connected.await(20,TimeUnit.SECONDS)||binder==null)throw new IOException("Не удалось запустить PDF-движок");
            count=call("open",args("path",source.getAbsolutePath())).json.getInt("count");
        }catch(Exception ex){close();throw new IOException("Не удалось открыть PDF: "+ex.getMessage(),ex);}
    }
    int count(){return count;}
    void setPart(int part){try{JSONArray box=call("part",args("part",part)).json.getJSONArray("bounds");bounds=rect(box);}catch(JSONException ex){throw new IllegalStateException(ex);}}
    Bitmap render(int pw,int ph,int x,int y,int w,int h){return call("render",args("pw",pw,"ph",ph,"x",x,"y",y,"w",w,"h",h)).bitmap;}
    Line select(float x,float y){JSONObject line=call("select",args("x",x,"y",y)).json.optJSONObject("line");return line==null?null:new Line(line);}
    Line insertion(float x,float y){return new Line(call("insert",args("x",x,"y",y)).json.optJSONObject("line"));}
    void replace(Line line,String text,float size,boolean remove){call("replace",args("line",line.data,"text",text,"size",size,"remove",remove));}
    void highlight(Line line){call("highlight",args("line",line.data));}
    void undo(){call("undo",args());}
    void redo(){call("redo",args());}
    void save(File target){call("save",args("path",target.getAbsolutePath()));}
    List<RectF> search(String query){try{JSONArray hits=call("search",args("query",query)).json.getJSONArray("matches");ArrayList<RectF> result=new ArrayList<>();for(int i=0;i<hits.length();i++)result.add(rect(hits.getJSONArray(i)));return result;}catch(JSONException ex){throw new IllegalStateException(ex);}}
    private static RectF rect(JSONArray box)throws JSONException{return new RectF((float)box.getDouble(0),(float)box.getDouble(1),(float)box.getDouble(2),(float)box.getDouble(3));}
    private static JSONObject args(Object... values){JSONObject json=new JSONObject();try{for(int i=0;i<values.length;i+=2)json.put((String)values[i],values[i+1]);}catch(JSONException ex){throw new IllegalArgumentException(ex);}return json;}
    private static final class Result{JSONObject json;Bitmap bitmap;}
    private Result call(String action,JSONObject request){
        IBinder remote=binder;if(remote==null)throw new IllegalStateException("PDF-движок остановлен. Закройте документ и откройте его снова.");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeString(action);data.writeString(request.toString());if(!remote.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0))throw new RemoteException("Операция PDF не поддерживается");reply.readException();Result result=new Result();result.json=new JSONObject(reply.readString());if(reply.readInt()!=0)result.bitmap=Bitmap.CREATOR.createFromParcel(reply);return result;}
        catch(RemoteException|JSONException ex){throw new IllegalStateException("Не удалось выполнить операцию PDF: "+ex.getMessage(),ex);}
        finally{data.recycle();reply.recycle();}
    }
    @Override public void close(){try{if(binder!=null&&binder.isBinderAlive())call("close",args());}catch(RuntimeException ignored){}finally{binder=null;if(bound){context.unbindService(connection);bound=false;}}}
}
