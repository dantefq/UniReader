package app.quietread;

import android.content.Intent;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Round-trip integration evidence, excluded from production builds. */
public final class QaUnicode122 {
    public static void run(ReaderEngine e,Intent intent,File files){ReaderEngine.WORKER.execute(()->{
        String name=intent.getStringExtra("qa_fixture");File directory=new File(files,"qa122/"+name);directory.mkdirs();
        String value="Привет, ёжик! № 42 — € ± × ¼ 中文 😀 𝄞";
        try{
            if(e.pdf!=null){
                PdfEngine.Line line=e.pdf.select(100,75);if(line==null)throw new IOException("No PDF text at fixture point");
                e.pdf.replace(line,value,line.size,true);e.export(new File(directory,"edited.pdf"),"pdf","");
            }else{
                if(e.type==1)e.command(".uno:GoToCell","{\"ToPoint\":{\"type\":\"string\",\"value\":\"A1\"}}");
                else {if(e.type==2)e.click(7200,2100,2,.05f);else if(e.type==3)e.click(1700,1300,2,.05f);else e.command(".uno:GoToStartOfDoc","{}");e.command(".uno:SelectAll","{}");}
                if(e.type==0){e.document.paste("text/plain;charset=utf-8",value);e.export(new File(directory,"legacy.odt"),"odt","");e.command(".uno:SelectAll","{}");}
                e.paste(value);String ext=e.type==1?"ods":e.type==2?"odp":e.type==3?"odg":"odt";e.export(new File(directory,"edited."+ext),ext,"");
                e.command(".uno:SelectAll","{}");try(FileOutputStream out=new FileOutputStream(new File(directory,"selection.txt"))){out.write(Utf8Bridge.selectedText(e.document).getBytes(StandardCharsets.UTF_8));}
                if(e.type==1){e.command(".uno:GoToCell","{\"ToPoint\":{\"type\":\"string\",\"value\":\"B2\"}}");e.paste("=IF(1=1;\"Привет 😀\";\"нет\")");e.export(new File(directory,"formula.ods"),"ods","");}
            }
            try(FileOutputStream out=new FileOutputStream(new File(directory,"expected.txt"))){out.write(value.getBytes(StandardCharsets.UTF_8));}
            new File(directory,"PASS").createNewFile();Log.i("UniReaderQA","UNICODE122 PASS "+name);
        }catch(Throwable ex){Log.e("UniReaderQA","UNICODE122 FAIL "+name,ex);try(FileWriter out=new FileWriter(new File(directory,"FAIL.txt"))){out.write(Log.getStackTraceString(ex));}catch(IOException ignored){}}
    });}
}
