package app.quietread;

import android.content.Context;
import android.graphics.Bitmap;
import com.artifex.mupdf.fitz.*;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;
import java.io.*;
import java.util.*;

/** Page-preserving PDF engine. All access belongs to ReaderEngine.WORKER. */
final class NativePdfEngine implements AutoCloseable {
    final PDFDocument doc;
    private PDFPage page;
    private DisplayList display;
    private List<Line> lines;
    private final File fontFile;
    Rect bounds;
    int part;
    static final class Line {
        Rect box; Point origin,dir; float size=12; int argb=0xff000000; String text="";
    }
    NativePdfEngine(Context context,File source)throws IOException {
        com.artifex.mupdf.fitz.Document opened=com.artifex.mupdf.fitz.Document.openDocument(source.getAbsolutePath());
        if(opened.needsPassword()){opened.destroy();throw new IOException("PDF защищён паролем");}
        doc=opened.asPDF();
        if(doc==null){opened.destroy();throw new IOException("Это не PDF");}
        opened.destroy();doc.disableJs();doc.enableJournal();
        fontFile=new File(context.getFilesDir(),"pdf-LiberationSans.ttf");
        if(!fontFile.isFile())try(InputStream in=context.getAssets().open("unpack/user/fonts/LiberationSans-Regular.ttf");OutputStream out=new FileOutputStream(fontFile)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}catch(IOException ex){fontFile.delete();doc.destroy();throw ex;}
    }
    int count(){return doc.countPages();}
    void setPart(int n){dropPage();part=n;page=(PDFPage)doc.loadPage(n);bounds=page.getBounds();}
    private void dropRaster(){if(display!=null){display.destroy();display=null;}lines=null;}
    private void dropPage(){dropRaster();if(page!=null){page.destroy();page=null;}}
    void render(Bitmap bitmap,int x,int y,int w,int h){
        if(display==null)display=page.toDisplayList();
        float sx=bitmap.getWidth()*20f/w,sy=bitmap.getHeight()*20f/h;
        Matrix matrix=new Matrix(sx,0,0,sy,-(x/20f+bounds.x0)*sx,-(y/20f+bounds.y0)*sy);
        AndroidDrawDevice device=new AndroidDrawDevice(bitmap);
        try{display.run(device,matrix,null);device.close();}finally{device.destroy();}
    }
    private List<Line> textLines(){
        if(lines!=null)return lines;ArrayList<Line> found=new ArrayList<>();StructuredText text=page.toStructuredText();
        try{text.walk(new StructuredTextWalker(){
            Line line;StringBuilder value;
            public void onImageBlock(Rect b,Matrix m,Image i){i.destroy();}
            public void beginTextBlock(Rect b,int f){}public void endTextBlock(){}
            public void beginLine(Rect b,int w,Point dir){line=new Line();line.box=new Rect(b);line.dir=dir;value=new StringBuilder();}
            public void endLine(){line.text=value.toString();if(!line.text.trim().isEmpty())found.add(line);}
            public void onChar(int c,Point origin,Font font,float size,Quad q,int color,int flags,int bidi){if(value.length()==0){line.origin=origin;line.size=size;line.argb=color;}value.appendCodePoint(c);font.destroy();}
            public void beginStruct(String s,String r,int i){}public void endStruct(){}public void onVector(Rect b,VectorInfo i,int c){}
        });}finally{text.destroy();}lines=found;return lines;
    }
    Line select(float x,float y){x+=bounds.x0;y+=bounds.y0;Line nearest=null;float distance=Float.MAX_VALUE;
        for(Line l:textLines()){float dx=Math.max(l.box.x0-x,Math.max(0,x-l.box.x1)),dy=Math.max(l.box.y0-y,Math.max(0,y-l.box.y1));float d=dx*dx+dy*dy;if(d<distance){distance=d;nearest=l;}}
        return distance<=64?nearest:null;
    }
    Line insertion(float x,float y){Line line=new Line();line.origin=new Point(x+bounds.x0,y+bounds.y0+12);line.dir=new Point(1,0);line.box=new Rect(line.origin.x,line.origin.y-12,bounds.x1-18,line.origin.y+4);return line;}
    void replace(Line selected,String value,float size,boolean removeOriginal){
        // Build and validate the replacement before touching the original content.
        PDFDocument overlay=value.isEmpty()?null:makeText(selected,value,size);
        doc.beginOperation(removeOriginal?"Заменить текст":"Добавить текст");
        try{
            if(removeOriginal){PDFAnnotation redact=page.createAnnotation(PDFAnnotation.TYPE_REDACT);
                try{Rect r=new Rect(selected.box);r.inset(.4f,.4f);redact.setRect(r);redact.addQuadPoint(new Quad(r));redact.applyRedaction(false,PDFPage.REDACT_IMAGE_NONE,PDFPage.REDACT_LINE_ART_NONE,PDFPage.REDACT_TEXT_REMOVE);}finally{redact.destroy();}}
            if(overlay!=null)appendOverlay(overlay);
            page.update();doc.endOperation();dropRaster();
        }catch(RuntimeException ex){doc.abandonOperation();dropRaster();throw ex;}finally{if(overlay!=null)overlay.destroy();}
    }
    private PDFDocument makeText(Line line,String value,float size){
        Font font=new Font(fontFile.getAbsolutePath());Buffer buffer=new Buffer();DocumentWriter writer=new DocumentWriter(buffer,"pdf","compress=yes");Text text=new Text();
        try{
            Device device=writer.beginPage(new Rect(bounds));float dx=line.dir.x,dy=line.dir.y;
            String[] rows=value.split("\n",-1);float y=line.origin.y,x=line.origin.x;
            for(int n=0;n<rows.length;n++)text.showString(font,new Matrix(size*dx,size*dy,size*dy,-size*dx,x-dy*n*size*1.3f,y+dx*n*size*1.3f),rows[n]);
            int color=line.argb;device.fillText(text,Matrix.Identity(),ColorSpace.DeviceRGB,new float[]{((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f},1,0);
            writer.endPage();device.destroy();writer.close();com.artifex.mupdf.fitz.Document result=com.artifex.mupdf.fitz.Document.openDocument(buffer.asByteArray(),"application/pdf");try{return result.asPDF();}finally{result.destroy();}
        }finally{text.destroy();font.destroy();writer.destroy();buffer.destroy();}
    }
    private PDFObject copyDict(PDFObject source){PDFObject copy=doc.newDictionary();if(!source.isNull())for(PDFObject key:source)copy.put(key,source.get(key));return copy;}
    private void appendOverlay(PDFDocument overlay){
        PDFPage added=(PDFPage)overlay.loadPage(0);Buffer contents=new Buffer();
        try{
            PDFObject addedPage=added.getObject(),streams=addedPage.get("Contents");
            if(streams.isArray())for(PDFObject stream:streams)contents.writeBytes(stream.readStream());else contents.writeBytes(streams.readStream());
            PDFObject form=doc.newDictionary();form.put("Type",doc.newName("XObject"));form.put("Subtype",doc.newName("Form"));form.put("BBox",doc.graftObject(addedPage.get("MediaBox")));form.put("Resources",doc.graftObject(addedPage.getInheritable("Resources")));
            PDFObject object=doc.addStream(contents,form),target=page.getObject();PDFObject resources=copyDict(target.getInheritable("Resources")),xobjects=copyDict(resources.get("XObject"));
            String name="UniEdit"+object.asIndirect();xobjects.put(name,object);resources.put("XObject",xobjects);target.put("Resources",resources);
            Matrix placement=new Matrix(added.getTransform(),Matrix.Inverted(page.getTransform()));
            String drawing=String.format(Locale.ROOT,"q %.8f %.8f %.8f %.8f %.8f %.8f cm /%s Do Q\n",placement.a,placement.b,placement.c,placement.d,placement.e,placement.f,name);
            PDFObject all=doc.newArray();all.push(doc.addStream("q\n"));PDFObject prior=target.get("Contents");if(prior.isArray())for(PDFObject stream:prior)all.push(stream);else if(!prior.isNull())all.push(prior);all.push(doc.addStream("\nQ\n"+drawing));target.put("Contents",all);
        }finally{contents.destroy();added.destroy();}
    }
    void highlight(Line line){doc.beginOperation("Выделить текст");try{PDFAnnotation a=page.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT);try{a.addQuadPoint(new Quad(line.box));a.setColor(new float[]{1,.8f,.1f});a.setOpacity(.4f);a.update();}finally{a.destroy();}doc.endOperation();dropRaster();}catch(RuntimeException ex){doc.abandonOperation();throw ex;}}
    void undo(){if(doc.canUndo()){doc.undo();setPart(part);}}
    void redo(){if(doc.canRedo()){doc.redo();setPart(part);}}
    void save(File file){
        // Graft the complete catalog, including forms, outlines and cyclic page references.
        // Serializing or renumbering the live xref would invalidate its undo journal.
        PDFDocument copy=new PDFDocument();
        try{PDFObject source=doc.getTrailer(),target=copy.getTrailer();target.put("Root",copy.graftObject(source.get("Root")));
            if(!source.get("Info").isNull())target.put("Info",copy.graftObject(source.get("Info")));
            if(doc.getVersion()>17)target.get("Root").put("Version",copy.newName((doc.getVersion()/10)+"."+(doc.getVersion()%10)));
            copy.save(file.getAbsolutePath(),"garbage=3,compress=yes");
        }finally{copy.destroy();}
    }
    Quad[][] search(String query){return page.search(query);}
    @Override public void close(){dropPage();doc.destroy();com.artifex.mupdf.fitz.Context.emptyStore();}
}
