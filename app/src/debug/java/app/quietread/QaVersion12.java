package app.quietread;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;
import java.io.*;
public final class QaVersion12 {
    static void png(ReaderEngine e,File dir,String name,int page)throws Exception{e.setPart(page);Bitmap b=e.image(page);try(OutputStream out=new FileOutputStream(new File(dir,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
    static void run(ReaderEngine e,Intent intent,File files){ReaderEngine.WORKER.execute(()->{File dir=new File(files,"qa12/"+intent.getStringExtra("qa_fixture"));dir.mkdirs();try{
        if(e.pdf!=null){
            png(e,dir,"before-1.png",0);png(e,dir,"before-2.png",1);png(e,dir,"before-3.png",2);e.setPart(0);
            PdfEngine.Line selected=e.pdf.select(100,75);if(selected==null)throw new IOException("No selected text");Log.i("UniReaderQA","SELECT "+selected.text+" size="+selected.size);
            e.pdf.replace(selected,"UNIREADER_PDF_12 Привет",selected.size,true);e.export(new File(dir,"edited.pdf"),"pdf","");
            png(e,dir,"after-1.png",0);png(e,dir,"after-2.png",1);png(e,dir,"after-3.png",2);e.setPart(0);
            e.pdf.undo();e.export(new File(dir,"undo.pdf"),"pdf","");e.pdf.redo();e.export(new File(dir,"redo.pdf"),"pdf","");
            e.setPart(1);android.graphics.RectF hit=e.pdf.search("ROTATED_PAGE_12").get(0);PdfEngine.Line rotated=e.pdf.select(hit.centerX(),hit.centerY());if(rotated==null)throw new IOException("Rotated text not selectable");e.pdf.replace(rotated,"ROTATE_EDIT_12",rotated.size,true);e.export(new File(dir,"rotated-edit.pdf"),"pdf","");
            e.setPart(2);hit=e.pdf.search("FORM_PAGE_12").get(0);e.pdf.highlight(e.pdf.select(hit.centerX(),hit.centerY()));e.export(new File(dir,"highlight.pdf"),"pdf","");
            e.setPart(0);e.pdf.replace(e.pdf.insertion(70,400),"ADDED_TEXT_12",16,false);e.export(new File(dir,"added.pdf"),"pdf","");
        }else if(e.type==1){for(String mode:new String[]{"pdf-fit","pdf-sheet","pdf-print"}){File out=ExportController.convert(new File(files,"../cache"),e,new ExportController.Format("pdf",mode,"application/pdf",mode),0);java.nio.file.Files.copy(out.toPath(),new File(dir,mode+".pdf").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}
        new File(dir,"PASS").createNewFile();Log.i("UniReaderQA","PASS "+intent.getStringExtra("qa_fixture"));
    }catch(Throwable ex){Log.e("UniReaderQA","FAIL",ex);try(FileWriter out=new FileWriter(new File(dir,"FAIL.txt"))){out.write(android.util.Log.getStackTraceString(ex));}catch(IOException ignored){}}});}
}
