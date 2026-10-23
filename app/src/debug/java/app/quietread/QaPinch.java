package app.quietread;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import java.io.*;

/** Debug-only real two-pointer input, with frame evidence captured at the flow boundaries. */
public final class QaPinch extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        String fixture=arguments.getString("fixture","wide12.xlsx");Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("qa_fixture",fixture));
        DocumentView[] document={null};for(int n=0;n<60&&document[0]==null;n++){runOnMainSync(()->document[0]=find(activity.getWindow().getDecorView()));SystemClock.sleep(100);}if(document[0]==null)throw new IOException("Document canvas not found");SystemClock.sleep(1500);
        int[] location=new int[2],size=new int[2];runOnMainSync(()->{document[0].getLocationOnScreen(location);size[0]=document[0].getWidth();size[1]=document[0].getHeight();});
        File dir=new File(getTargetContext().getFilesDir(),"qa12/pinch-"+fixture);dir.mkdirs();shell("dumpsys gfxinfo app.quietread reset");
        float[] zoom={0,0};runOnMainSync(()->zoom[0]=document[0].zoomValue());
        for(int n=0;n<6;n++){pinch(location[0]+size[0]/2f,location[1]+size[1]/2f,180,360);if(n==0){runOnMainSync(()->zoom[1]=document[0].zoomValue());if(zoom[1]<zoom[0]*1.3f)throw new IOException("Pinch did not scale: "+zoom[0]+" -> "+zoom[1]);}pinch(location[0]+size[0]/2f,location[1]+size[1]/2f,360,180);}
        SystemClock.sleep(800);write(new File(dir,"gfxinfo.txt"),shell("dumpsys gfxinfo app.quietread framestats"));write(new File(dir,"meminfo.txt"),shell("dumpsys meminfo app.quietread"));
        runOnMainSync(()->document[0].detach());result.putString("result","PASS "+fixture);finish(Activity.RESULT_OK,result);
    }catch(Exception ex){result.putString("error",android.util.Log.getStackTraceString(ex));finish(Activity.RESULT_CANCELED,result);}}
    private static DocumentView find(View view){if(view instanceof DocumentView)return (DocumentView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){DocumentView found=find(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private void pinch(float cx,float cy,float start,float end){
        long down=SystemClock.uptimeMillis();send(down,down,MotionEvent.ACTION_DOWN,cx,cy,start,1);send(down,down+16,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),cx,cy,start,2);
        for(int i=1;i<=16;i++){SystemClock.sleep(16);send(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,cx,cy,start+(end-start)*i/16,2);}
        send(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),cx,cy,end,2);send(down,SystemClock.uptimeMillis()+1,MotionEvent.ACTION_UP,cx,cy,end,1);SystemClock.sleep(150);
    }
    private void send(long down,long time,int action,float cx,float cy,float distance,int count){MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] points=new MotionEvent.PointerCoords[count];for(int i=0;i<count;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;points[i]=new MotionEvent.PointerCoords();points[i].x=cx+(i==0?-distance:distance);points[i].y=cy;points[i].pressure=1;points[i].size=1;}MotionEvent event=MotionEvent.obtain(down,time,action,count,props,points,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);try{if(!getUiAutomation().injectInputEvent(event,true))throw new IllegalStateException("Touch injection failed");}finally{event.recycle();}}
    private byte[] shell(String command)throws IOException{try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1)bytes.write(chunk,0,n);return bytes.toByteArray();}}
    private static void write(File file,byte[] data)throws IOException{try(OutputStream out=new FileOutputStream(file)){out.write(data);}}
}
