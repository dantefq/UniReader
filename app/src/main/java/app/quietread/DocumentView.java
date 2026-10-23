package app.quietread;

import android.content.Context;
import android.graphics.*;
import android.util.LruCache;
import android.view.*;
import android.widget.OverScroller;
import java.util.*;

/** Native, virtualized tiled canvas. Never rasterizes a whole document into memory. */
final class DocumentView extends View {
    static final int TILE=384;
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint paper=new Paint();
    private final RectF tileRect=new RectF();
    private static final class Tile {
        final Bitmap bitmap; final int x,y,size,revision;
        Tile(Bitmap b,int x,int y,int s,int r){bitmap=b;this.x=x;this.y=y;size=s;revision=r;}
    }
    private final LruCache<String,Tile> cache=new LruCache<String,Tile>(32*1024*1024) {
        @Override protected int sizeOf(String key,Tile tile) { return tile.bitmap.getAllocationByteCount(); }
    };
    private final Set<String> pending=new HashSet<>();
    private final ArrayList<Tile> retained=new ArrayList<>();
    private void retain(){retained.clear();retained.addAll(cache.snapshot().values());retained.sort(Comparator.comparingInt((Tile t)->t.revision).thenComparingInt(t->-t.size));}
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;
    private final OverScroller scroller;
    private ReaderEngine engine;
    private float scale=.05f, minScale=.01f, offsetX, offsetY;
    private volatile int epoch;
    private volatile int wantedSize;
    private boolean night;
    private long lastReport;
    private RectF highlight;
    private RectF cursor;
    private boolean editing;
    private final boolean debug;
    private boolean hadRaster;
    private int drawnFrames,emptyFrames,fallbackFrames;
    private Bitmap preview;
    private final RectF previewBounds=new RectF();
    private boolean scaling;
    interface Tap { void click(int x,int y,int count,float zoom); }
    private Tap tap;
    interface Listener { void position(float x,float y,float scale); void renderError(String message); }
    Listener listener;

    DocumentView(Context context) {
        super(context); setContentDescription("Документ. Два пальца — масштаб, один — прокрутка");
        debug=(context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        setFocusable(true);
        scroller=new OverScroller(context);
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){capturePreview();scaling=true;wantedSize=0;return true;}
            @Override public void onScaleEnd(ScaleGestureDetector detector){scaling=false;invalidate();}
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom(scale*detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY()); return true;
            }
        });
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e) { scroller.forceFinished(true); return true; }
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy) {
                if (!pinch.isInProgress()) { offsetX+=dx; offsetY+=dy; clamp(); invalidate(); report(); } return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if(editing){click(e,2);return true;}
                zoom(scale>minScale*1.6f?minScale:Math.min(minScale*2.5f,.5f),e.getX(),e.getY()); return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e){if(editing)click(e,1);return true;}
            @Override public boolean onFling(MotionEvent a,MotionEvent b,float vx,float vy) {
                if(engine!=null) {
                    scroller.fling((int)offsetX,(int)offsetY,(int)-vx,(int)-vy,0,maxX(),0,maxY()); postInvalidateOnAnimation();
                } return true;
            }
        });
    }
    void attach(ReaderEngine value, float x,float y,float oldScale) {
        engine=value; epoch++; cache.evictAll();retained.clear();pending.clear();preview=null;highlight=null;hadRaster=false;drawnFrames=emptyFrames=fallbackFrames=0;
        fit();
        if (oldScale>0) scale=Math.max(minScale,Math.min(.7f,oldScale));
        offsetX=x*scale; offsetY=y*scale; clamp(); invalidate(); report();
    }
    void detach() {if(debug&&engine!=null)android.util.Log.i("UniReaderQA","RASTER frames="+drawnFrames+" emptyAfterWarm="+emptyFrames+" fallback="+fallbackFrames);engine=null; epoch++; pending.clear(); cache.evictAll();retained.clear();preview=null;invalidate(); }
    // Keep the previous raster visible until its replacements arrive, including after edits.
    void refresh(){epoch++;pending.clear();clamp();invalidate();report();}
    void setEditing(boolean enabled,Tap listener){editing=enabled;tap=listener;if(!enabled)cursor=null;invalidate();}
    void setCursor(RectF rect){cursor=rect;invalidate();}
    private void click(MotionEvent event,int count){
        if(engine==null||tap==null)return;
        float left=Math.max(dp(12),(getWidth()-engine.width*scale)/2f)-offsetX;
        int x=Math.round((event.getX()-left)/scale),y=Math.round((event.getY()-dp(12)+offsetY)/scale);
        if(x>=0&&y>=0&&x<=engine.width&&y<=engine.height)tap.click(x,y,count,scale);
    }
    void setNight(boolean value) {
        night=value;
        if (night) {
            ColorMatrix m=new ColorMatrix(new float[]{-.88f,0,0,0,242,0,-.88f,0,0,243,0,0,-.88f,0,239,0,0,0,1,0});
            paint.setColorFilter(new ColorMatrixColorFilter(m));
        } else paint.setColorFilter(null);
        invalidate();
    }
    void fit() {
        if (engine==null || getWidth()==0) return;
        float fitWidth=(getWidth()-dp(24))/(float)engine.width;
        minScale=Math.min(.1f, fitWidth);
        // Calc's used area may be miles wide: start near the first cells at a readable zoom.
        scale=engine.type==1?Math.max(minScale,getResources().getDisplayMetrics().density/15f):minScale;
        if (engine.type==2 || engine.type==3) scale=Math.min(scale,(getHeight()-dp(24))/(float)engine.height);
        minScale=Math.min(minScale,scale);
        offsetX=offsetY=0; invalidate(); report();
    }
    void zoomBy(float factor) {capturePreview();zoom(scale*factor,getWidth()/2f,getHeight()/2f); }
    private void capturePreview(){
        if(engine==null||getWidth()==0||getHeight()==0||retained.isEmpty())return;
        float left=Math.max(dp(12),(getWidth()-engine.width*scale)/2f)-offsetX,top=dp(12)-offsetY;
        RectF area=new RectF(-left/scale,-top/scale,(getWidth()-left)/scale,(getHeight()-top)/scale);
        if(!area.intersect(0,0,engine.width,engine.height))return;
        if(preview!=null){RectF union=new RectF(area);union.union(previewBounds);if((double)union.width()*union.height()<=(double)area.width()*area.height()*16)area=union;}
        float ratio=Math.min(scale,(float)Math.sqrt(786432d/((double)area.width()*area.height())));
        try{Bitmap next=Bitmap.createBitmap(Math.max(1,(int)Math.ceil(area.width()*ratio)),Math.max(1,(int)Math.ceil(area.height()*ratio)),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(next);canvas.scale(ratio,ratio);canvas.translate(-area.left,-area.top);
            Paint raw=new Paint(Paint.FILTER_BITMAP_FLAG);if(preview!=null)canvas.drawBitmap(preview,null,previewBounds,raw);
            for(Tile tile:retained){tileRect.set(tile.x,tile.y,tile.x+tile.size,tile.y+tile.size);if(RectF.intersects(tileRect,area))canvas.drawBitmap(tile.bitmap,null,tileRect,raw);}
            preview=next;previewBounds.set(area);
        }catch(OutOfMemoryError ignored){/* Existing tiles remain usable when a snapshot cannot be allocated. */}
    }
    private void zoom(float value,float focusX,float focusY) {
        float next=Math.max(minScale,Math.min(.7f,value));
        if(next==scale) return;
        float oldLeft=Math.max(dp(12),(getWidth()-engine.width*scale)/2f),newLeft=Math.max(dp(12),(getWidth()-engine.width*next)/2f);
        offsetX=(offsetX+focusX-oldLeft)*next/scale+newLeft-focusX;offsetY=(offsetY+focusY-dp(12))*next/scale+dp(12)-focusY;
        scale=next; clamp(); invalidate(); report();
    }
    void jump(float x,float y,RectF selection) {
        highlight=selection; offsetX=Math.max(0,x*scale-dp(20)); offsetY=Math.max(0,y*scale-dp(60)); clamp(); invalidate(); report();
    }
    float docX() { return offsetX/scale; }
    float docY() { return offsetY/scale; }
    float zoomValue() { return scale; }
    private int dp(float value) { return (int)(value*getResources().getDisplayMetrics().density+.5f); }
    private int maxX() { return engine==null?0:Math.max(0,(int)Math.min(Integer.MAX_VALUE/2,engine.width*scale)-getWidth()+dp(24)); }
    private int maxY() { return engine==null?0:Math.max(0,(int)Math.min(Integer.MAX_VALUE/2,engine.height*scale)-getHeight()+dp(24)); }
    private void clamp() { offsetX=Math.max(0,Math.min(maxX(),offsetX)); offsetY=Math.max(0,Math.min(maxY(),offsetY)); }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh) {
        if(engine!=null) { if(ow==0) fit(); else { minScale=Math.min(.1f,(w-dp(24))/(float)engine.width); scale=Math.max(scale,minScale);if(editing&&cursor!=null&&h<oh&&dp(12)+cursor.bottom*scale-offsetY>h-dp(16))offsetY=dp(28)+cursor.bottom*scale-h;clamp(); } }
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e); gestures.onTouchEvent(e);
        if(e.getActionMasked()==MotionEvent.ACTION_UP) { performClick(); report(); }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void computeScroll() {
        if(scroller.computeScrollOffset()) { offsetX=scroller.getCurrX(); offsetY=scroller.getCurrY(); clamp(); postInvalidateOnAnimation(); report(); }
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(night?0xff050607:Color.rgb(229,231,225));
        ReaderEngine current=engine;
        if(current==null || current.closed) return;
        float left=Math.max(dp(12),(getWidth()-current.width*scale)/2f)-offsetX;
        float top=dp(12)-offsetY;
        paper.setColor(night?0xff121314:Color.WHITE);
        canvas.drawRect(left,top,left+current.width*scale,top+current.height*scale,paper);
        // Quantized resolution makes neighboring tiles reusable during a pinch.
        float rasterScale=(float)(Math.pow(2,Math.round(Math.log(scale)/Math.log(2)*4)/4.0));
        float step=Math.max(1,Math.round(TILE/rasterScale));
        wantedSize=scaling?0:(int)step;
        int x0=Math.max(0,(int)((-left)/scale/step));
        int y0=Math.max(0,(int)((-top)/scale/step));
        int x1=(int)Math.min(Math.ceil(current.width/step)-1,Math.ceil((getWidth()-left)/scale/step));
        int y1=(int)Math.min(Math.ceil(current.height/step)-1,Math.ceil((getHeight()-top)/scale/step));
        int save=canvas.save(); canvas.clipRect(left,top,left+current.width*scale,top+current.height*scale);
        // Draw older resolutions beneath fresh tiles. A pinch must never reveal an empty page.
        boolean drewRaster=false,usedFallback=false;
        if(preview!=null){tileRect.set(left+previewBounds.left*scale,top+previewBounds.top*scale,left+previewBounds.right*scale,top+previewBounds.bottom*scale);canvas.drawBitmap(preview,null,tileRect,paint);drewRaster=true;usedFallback=true;}
        if(scaling&&preview!=null){if(debug){drawnFrames++;fallbackFrames++;hadRaster=true;}canvas.restoreToCount(save);return;}
        for(int y=y0;y<=y1;y++) for(int x=x0;x<=x1;x++) {
            String key=epoch+":"+rasterScale+":"+x+":"+y;
            Tile tile=cache.get(key);
            float px=x*step,py=y*step;
            if(tile!=null) { tileRect.set(left+px*scale,top+py*scale,left+(px+step)*scale,top+(py+step)*scale); canvas.drawBitmap(tile.bitmap,null,tileRect,paint);drewRaster=true; }
            else {
                if(preview==null){int fallback=canvas.save();canvas.clipRect(left+px*scale,top+py*scale,left+(px+step)*scale,top+(py+step)*scale);
                    for(Tile old:retained)if(old.x<px+step&&old.x+old.size>px&&old.y<py+step&&old.y+old.size>py){tileRect.set(left+old.x*scale,top+old.y*scale,left+(old.x+old.size)*scale,top+(old.y+old.size)*scale);canvas.drawBitmap(old.bitmap,null,tileRect,paint);drewRaster=true;usedFallback=true;}
                    canvas.restoreToCount(fallback);}
                if(pending.size()<2&&pending.add(key))request(current,key,epoch,(int)px,(int)py,(int)step);
            }
        }
        if(debug){drawnFrames++;if(hadRaster&&!drewRaster)emptyFrames++;if(usedFallback)fallbackFrames++;hadRaster|=drewRaster;}
        if(highlight!=null) {
            paper.setColor(0x66FFC857); canvas.drawRect(left+highlight.left*scale,top+highlight.top*scale,left+highlight.right*scale,top+highlight.bottom*scale,paper);
        }
        if(editing&&cursor!=null){paper.setColor(0xff356859);paper.setStyle(Paint.Style.STROKE);paper.setStrokeWidth(dp(2));canvas.drawRect(left+cursor.left*scale,top+cursor.top*scale,left+cursor.right*scale,top+cursor.bottom*scale,paper);paper.setStyle(Paint.Style.FILL);}
        canvas.restoreToCount(save);
    }
    private void request(ReaderEngine current,String key,int requestEpoch,int x,int y,int size) {
        ReaderEngine.WORKER.execute(()->{
            if(current.closed || requestEpoch!=epoch || wantedSize!=size) { post(()->{pending.remove(key);invalidate();}); return; }
            try {
                Bitmap bitmap=current.render(TILE,x,y,size,size);
                post(()->{
                    pending.remove(key);
                    if(bitmap==null) return;
                    if(requestEpoch!=epoch) { bitmap.recycle(); return; }
                    cache.put(key,new Tile(bitmap,x,y,size,requestEpoch));retain();invalidate();
                });
            } catch(RuntimeException | OutOfMemoryError ex) {
                post(()->{ pending.remove(key); if(requestEpoch==epoch && listener!=null) listener.renderError("Не удалось отрисовать область. Уменьшите масштаб или закройте другие приложения."); });
            }
        });
    }
    private void report() {
        long now=android.os.SystemClock.uptimeMillis();
        if(listener!=null && now-lastReport>150) { lastReport=now; listener.position(docX(),docY(),scale); }
    }
}
