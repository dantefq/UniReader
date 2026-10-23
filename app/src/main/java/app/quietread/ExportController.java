package app.quietread;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Converts privately first, then writes only to the destination selected with SAF. */
final class ExportController {
    static final int CREATE=42;
    static final class Format {
        final String ext,label,mime,engineExt;
        Format(String ext,String label,String mime){this(ext,label,mime,ext);}
        Format(String ext,String label,String mime,String engineExt){this.ext=ext;this.label=label;this.mime=mime;this.engineExt=engineExt;}
    }
    final Activity activity;final ReaderEngine engine;final String name;final java.util.function.Consumer<String> error;
    private final Handler main=new Handler(Looper.getMainLooper());
    private File pending;boolean busy;private boolean closed;private ProgressDialog progress;
    ExportController(Activity a,ReaderEngine e,String n,java.util.function.Consumer<String> err){activity=a;engine=e;name=n;error=err;}
    static List<Format> formats(int type){
        ArrayList<Format> list=new ArrayList<>();
        if(type>=0){
            list.add(new Format("pdf","PDF — все страницы / листы / слайды","application/pdf"));
            String specs=type==1?"xlsx|Excel XLSX;xls|Excel 97–2003;ods|LibreOffice ODS;csv|CSV — текущий лист;fods|Плоский FODS;ots|Шаблон OTS":type==2?"pptx|PowerPoint PPTX;ppt|PowerPoint 97–2003;odp|LibreOffice ODP;fodp|Плоский FODP;otp|Шаблон OTP":type==3?"odg|LibreOffice Draw;fodg|Плоский FODG;svg|SVG — текущая страница":"docx|Word DOCX;doc|Word 97–2003;odt|LibreOffice ODT;rtf|RTF;txt|Обычный текст UTF-8;epub|Книга EPUB;fodt|Плоский FODT;ott|Шаблон OTT";
            for(String entry:specs.split(";")){String[] s=entry.split("\\|");list.add(new Format(s[0],s[1],mime(s[0])));}
            if(type==0||type==1)list.add(new Format("zip","HTML с ресурсами — ZIP","application/zip","html"));
        }
        list.add(new Format("png",type==0||type==-1?"PNG — текущая страница":"PNG — текущий лист / слайд","image/png"));
        list.add(new Format("jpg",type==0||type==-1?"JPEG — текущая страница":"JPEG — текущий лист / слайд","image/jpeg"));
        if(type==-1)list.add(new Format("zip","PNG всех страниц — ZIP","application/zip","pdf-images"));
        if(type==-1)list.add(0,new Format("pdf","PDF — сохранить документ","application/pdf"));
        return list;
    }
    static String mime(String ext){switch(ext){case"docx":return"application/vnd.openxmlformats-officedocument.wordprocessingml.document";case"xlsx":return"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";case"pptx":return"application/vnd.openxmlformats-officedocument.presentationml.presentation";case"doc":return"application/msword";case"xls":return"application/vnd.ms-excel";case"ppt":return"application/vnd.ms-powerpoint";case"odt":return"application/vnd.oasis.opendocument.text";case"ods":return"application/vnd.oasis.opendocument.spreadsheet";case"odp":return"application/vnd.oasis.opendocument.presentation";case"odg":return"application/vnd.oasis.opendocument.graphics";case"rtf":return"application/rtf";case"csv":return"text/csv";case"txt":return"text/plain";case"epub":return"application/epub+zip";case"svg":return"image/svg+xml";default:return"application/octet-stream";}}
    void choose(int page){if(busy)return;List<Format> list=formats(engine.type);String[] labels=new String[list.size()];for(int i=0;i<labels.length;i++)labels[i]=list.get(i).label;
        new AlertDialog.Builder(activity).setTitle("Сохранить / конвертировать").setItems(labels,(d,w)->{
            Format f=list.get(w);
            if(f.ext.equals("pdf")&&engine.type==1){new AlertDialog.Builder(activity).setTitle("Таблица → PDF").setItems(new String[]{"Все столбцы по ширине страницы","Каждый лист целиком на одной странице","Как в настройках печати файла"},(a,b)->prepare(new Format("pdf",f.label,f.mime,b==0?"pdf-fit":b==1?"pdf-sheet":"pdf-print"),page)).show();}
            else if(f.ext.equals("csv")||f.ext.equals("txt")||f.ext.equals("png")||f.ext.equals("jpg"))new AlertDialog.Builder(activity).setTitle(f.label).setMessage(f.ext.equals("csv")?"Сохранится текущий лист: значения и текст без оформления. Для всей книги выберите XLSX или ODS.":f.ext.equals("txt")?"Сохранится текст без изображений, таблиц и оформления.":"Сохранится одно изображение. Максимальная сторона — 4096 пикселей. Текст на изображении нельзя выделять.").setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(a,b)->prepare(f,page)).show();
            else prepare(f,page);
        }).show();}
    private void showProgress(String message){busy=true;progress=new ProgressDialog(activity);progress.setMessage(message);progress.setCancelable(false);progress.show();}
    private void done(){busy=false;if(progress!=null){progress.dismiss();progress=null;}}
    void prepare(Format format,int page){
        showProgress("Конвертируем документ…");
        ReaderEngine.WORKER.execute(()->{try{
            File file=convert(activity.getCacheDir(),engine,format,page);
            main.post(()->{done();if(closed||activity.isDestroyed()){delete(file.getParentFile());return;}pending=file;String base=name.replaceFirst("\\.[^.]+$","");Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(format.mime).putExtra(Intent.EXTRA_TITLE,base+"-export."+format.ext);activity.startActivityForResult(intent,CREATE);});
        }catch(Exception|OutOfMemoryError ex){main.post(()->{done();if(!closed)error.accept("Не удалось конвертировать: "+ex.getMessage());});}});
    }
    static File convert(File cache,ReaderEngine engine,Format format,int page) throws Exception {
        File directory=new File(cache,"export-"+UUID.randomUUID());if(!directory.mkdirs())throw new IOException("Нет места для экспорта");
        File target=new File(directory,"document."+format.engineExt);
        try{
            if(format.ext.equals("pdf")&&engine.type==1){
                target=new File(directory,"document.pdf");
                if(format.engineExt.equals("pdf-print"))engine.export(target,"pdf","");
                else if(format.engineExt.equals("pdf-sheet"))engine.export(target,"pdf",",FullSheetPreview=trueFULLSHEETPREVEND");
                else SpreadsheetPdf.exportFit(engine,directory,target);
            }else if(format.engineExt.equals("pdf-images")){
                target=new File(directory,"pages.zip");int original=engine.part;
                try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(target))){try{for(int i=0;i<engine.parts;i++){engine.setPart(i);Bitmap bitmap=engine.image(i);try{zip.putNextEntry(new ZipEntry(String.format(Locale.ROOT,"page-%04d.png",i+1)));if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,zip))throw new IOException("Не удалось записать изображение");zip.closeEntry();}finally{bitmap.recycle();}}}finally{engine.setPart(original);}}
            }else if(format.ext.equals("png")||format.ext.equals("jpg")){
                target=new File(directory,"page."+format.ext);Bitmap bitmap=engine.image(page);
                try(OutputStream out=new FileOutputStream(target)){if(!bitmap.compress(format.ext.equals("png")?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,92,out))throw new IOException("Не удалось записать изображение");}finally{bitmap.recycle();}
            }else{
                String options=format.ext.equals("csv")?"44,34,76,1,,0,false,true,false,false,false,0":format.ext.equals("txt")?"UTF8":"";
                engine.export(target,format.engineExt,options);
                if(format.ext.equals("zip")){
                    File zipFile=new File(directory,"html.zip");
                    try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(zipFile))){pack(directory,directory,zip,zipFile);}
                    target=zipFile;
                }
            }
            if(target.length()==0)throw new IOException("Получился пустой файл");return target;
        }catch(Exception|OutOfMemoryError ex){delete(directory);throw ex;}
    }
    private static void pack(File root,File directory,ZipOutputStream out,File exclude)throws IOException{File[] files=directory.listFiles();if(files==null)return;byte[] buf=new byte[65536];for(File file:files){if(file.equals(exclude))continue;if(file.isDirectory())pack(root,file,out,exclude);else{out.putNextEntry(new ZipEntry(root.toPath().relativize(file.toPath()).toString().replace('\\','/')));try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}out.closeEntry();}}}
    void result(int result,Intent data){
        File file=pending;pending=null;if(file==null)return;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null){delete(file.getParentFile());return;}
        Uri uri=data.getData();showProgress("Сохраняем файл…");
        ReaderEngine.WORKER.execute(()->{try{
            try(InputStream in=new FileInputStream(file);OutputStream out=activity.getContentResolver().openOutputStream(uri,"wt")){
                if(out==null)throw new IOException("Нет доступа к выбранной папке");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            main.post(()->{done();if(!closed)Toast.makeText(activity,"Файл сохранён",Toast.LENGTH_LONG).show();});
        }catch(Exception ex){main.post(()->{done();if(!closed)error.accept("Не удалось сохранить файл: "+ex.getMessage()+". Проверьте место и доступ к папке.");});}
        finally{delete(file.getParentFile());}});
    }
    void close(){closed=true;if(pending!=null){delete(pending.getParentFile());pending=null;}if(progress!=null){progress.dismiss();progress=null;}}
    private static void delete(File file){if(file.isDirectory()){File[] all=file.listFiles();if(all!=null)for(File f:all)delete(f);}file.delete();}
}
