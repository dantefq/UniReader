package app.quietread;

import android.content.Intent;
import android.util.Log;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Debug-only integration harness; no class or test routes exist in release APKs. */
public final class QaExports {
    public static void run(ReaderEngine engine,Intent intent,File files){
        if(intent.getBooleanExtra("qa_unicode122",false)){QaUnicode122.run(engine,intent,files);return;}
        if(intent.getBooleanExtra("qa_check12",false)){QaVersion12.run(engine,intent,files);return;}
        if(!intent.getBooleanExtra("qa_export_all",false))return;
        ReaderEngine.WORKER.execute(()->{
            JSONArray results=new JSONArray();
            String fixture=intent.getStringExtra("qa_fixture");
            if(fixture==null||!fixture.matches("[a-zA-Z0-9_.-]+"))return;
            File root=new File(files,"qa-exports/"+fixture);root.mkdirs();
            for(ExportController.Format format:ExportController.formats(engine.type)){
                JSONObject result=new JSONObject();
                try{File generated=ExportController.convert(new File(files,"../cache"),engine,format,0);File copy=new File(root,"result."+format.ext);Files.copy(generated.toPath(),copy.toPath(),StandardCopyOption.REPLACE_EXISTING);result.put("format",format.ext).put("bytes",copy.length()).put("ok",true);}
                catch(Exception|OutOfMemoryError ex){try{result.put("format",format.ext).put("ok",false).put("error",ex.toString());}catch(JSONException ignored){}}
                results.put(result);Log.i("QuietReadQA",fixture+" "+result);
            }
            try(FileOutputStream out=new FileOutputStream(new File(root,"results.json"))){out.write(results.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ex){Log.e("QuietReadQA","report",ex);}
            Log.i("QuietReadQA","EXPORT_COMPLETE "+fixture);
        });
    }
}
