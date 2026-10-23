package app.quietread;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final int OPEN=41;
    private static final int GREEN=0xff356859, INK=0xff20352f, MUTED=0xff76817a, BG=0xfff5f4ef;
    private Library library;
    private Library.Item item;
    private ReaderEngine engine;
    private EditorController editor;
    private ExportController exporter;
    private ProgressDialog pdfProgress;
    private DocumentView documentView;
    private LinearLayout root, recentList;
    private TextView location, title;
    private TextView editStatus;
    private AlertDialog errorDialog;
    private boolean night, loading, destroyed;
    private int generation, filter, partRequest;
    private String filenameQuery="";
    private long loadStarted;
    private final Handler main=new Handler(Looper.getMainLooper());
    private int bg(){return night?0xff080a0b:BG;}
    private int fg(){return night?0xffeff3f0:INK;}
    private int panel(){return night?0xff15191b:Color.WHITE;}
    private int accent(){return night?0xff96d8ba:GREEN;}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}

    @Override public void onCreate(Bundle state) {
        night=getSharedPreferences("library",0).getBoolean("night",false);setTheme(night?R.style.AppThemeNight:R.style.AppTheme);
        super.onCreate(state); library=new Library(this);
        home(); handleIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    private void handleIntent(Intent intent) {
        Uri uri=intent.getData();
        if(Intent.ACTION_SEND.equals(intent.getAction())) uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if(uri!=null) { importDocument(uri); return; }
        // Debug-only fixture route is excluded from release behavior and requires an exact asset basename.
        String fixture=intent.getStringExtra("qa_fixture");
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0 && fixture!=null && fixture.matches("[a-zA-Z0-9_.-]+")) {
            try {
                File f=new File(getCacheDir(),fixture);
                try(InputStream in=getAssets().open("qa/"+fixture);OutputStream out=new FileOutputStream(f)) { byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n); }
                importDocument(Uri.fromFile(f));
            } catch(IOException ex) { error(ex.getMessage()); }
        }
    }
    private void base() {
        setTheme(night?R.style.AppThemeNight:R.style.AppTheme);
        if(errorDialog!=null){errorDialog.dismiss();errorDialog=null;}
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(bg());
        root.setFitsSystemWindows(true);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{ v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets.consumeSystemWindowInsets(); });
        getWindow().setStatusBarColor(bg()); getWindow().setNavigationBarColor(bg());
        getWindow().getDecorView().setSystemUiVisibility(night?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    private TextView text(String content,float sp,int color) {
        TextView v=new TextView(this); v.setText(content); v.setTextSize(sp); v.setTextColor(color); v.setFontFeatureSettings("kern"); return v;
    }
    private TextView bold(String content,float sp,int color) { TextView v=text(content,sp,color);v.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));return v; }
    private GradientDrawable shape(int color,int radius) { GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d; }
    private LinearLayout vertical() { LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v; }
    private void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    private void space(LinearLayout into,int height){into.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private TextView button(String label,Runnable action) {
        TextView v=bold(label,14,fg());v.setGravity(Gravity.CENTER); pad(v,14,12);v.setMinHeight(dp(48));v.setBackground(shape(panel(),16));v.setContentDescription(label);v.setOnClickListener(w->action.run());return v;
    }
    private View iconButton(String icon,String label,Runnable action) {
        Glyph v=new Glyph(this,icon,accent());v.setContentDescription(label);v.setOnClickListener(w->action.run());v.setBackground(shape(night?0xff1a2420:0xffe7ece2,16));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return v;
    }
    private void home() {
        base(); filter=0; filenameQuery="";
        LinearLayout heading=row();pad(heading,24,18);
        LinearLayout labels=vertical(); labels.addView(bold("UniReader",15,accent())); labels.addView(bold("Документы",32,fg()));
        heading.addView(labels,new LinearLayout.LayoutParams(0,-2,1));heading.addView(iconButton("more","Настройки",this::homeMenu)); root.addView(heading);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content=vertical();pad(content,24,4);scroll.addView(content);
        LinearLayout hero=vertical();pad(hero,22,22);hero.setBackground(shape(night?0xff14251e:GREEN,24));
        LinearLayout heroTop=row();
        TextView mark=bold("ВАШИ ФАЙЛЫ. ВАШЕ ПРОСТРАНСТВО.",10,0xffd2e4d6);heroTop.addView(mark,new LinearLayout.LayoutParams(0,-2,1));
        Glyph openIcon=new Glyph(this,"file",0xffd2e4d6);heroTop.addView(openIcon,new LinearLayout.LayoutParams(dp(32),dp(32)));hero.addView(heroTop);
        space(hero,8);hero.addView(bold("Читайте, правьте,\nконвертируйте.",29,Color.WHITE));space(hero,10);
        hero.addView(text("Word, PDF, таблицы и презентации.\nЛокально. Без рекламы и регистрации.",14,0xffd2e4d6));space(hero,20);
        TextView open=button("Открыть документ  +",this::pick);open.setTextColor(night?0xff10261a:GREEN);open.setBackground(shape(night?0xff96d8ba:0xfff3f2e9,14));hero.addView(open);content.addView(hero);
        space(content,20);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("Найти в недавних файлах");search.setTextSize(14);search.setTextColor(fg());search.setHintTextColor(MUTED);pad(search,16,8);search.setBackground(shape(panel(),14));
        content.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){filenameQuery=s.toString();renderRecents();}public void afterTextChanged(Editable e){}});
        space(content,16);
        HorizontalScrollView filters=new HorizontalScrollView(this);filters.setHorizontalScrollBarEnabled(false);LinearLayout chips=row();
        String[] chipLabels={"Все","PDF","Тексты","Таблицы","Слайды"};
        for(int i=0;i<chipLabels.length;i++) {
            final int index=i;TextView chip=button(chipLabels[i],()->{});chip.setTextSize(12);chip.setMinHeight(dp(40));pad(chip,13,8);
            chip.setTextColor(i==0?Color.WHITE:fg());chip.setBackground(shape(i==0?GREEN:panel(),12));
            chip.setOnClickListener(v->{filter=index;for(int j=0;j<chips.getChildCount();j++){TextView t=(TextView)chips.getChildAt(j);t.setTextColor(j==index?Color.WHITE:fg());t.setBackground(shape(j==index?GREEN:panel(),12));}renderRecents();});
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,dp(40));cp.setMarginEnd(dp(8));chips.addView(chip,cp);
        }
        filters.addView(chips);content.addView(filters);space(content,24);
        TextView label=bold("НЕДАВНИЕ · ЛОКАЛЬНЫЕ КОПИИ",11,MUTED);label.setLetterSpacing(.08f);content.addView(label);space(content,10);
        recentList=vertical();content.addView(recentList);renderRecents();space(content,18);
        TextView footer=text("На устройстве  •  Без рекламы\nUniReader · локально на устройстве",11,MUTED);footer.setGravity(Gravity.CENTER);pad(footer,8,18);content.addView(footer);
    }
    private void renderRecents() {
        if(recentList==null)return;recentList.removeAllViews();int count=0;
        for(Library.Item entry:library.all()) {
            if(filter!=0 && Library.family(entry.name)!=filter)continue;
            if(!entry.name.toLowerCase(Locale.ROOT).contains(filenameQuery.toLowerCase(Locale.ROOT)))continue;
            LinearLayout r=row();pad(r,12,12);r.setBackground(shape(panel(),16));
            int[] colors={GREEN,0xffad594d,0xff4e6f9b,0xff4c8063,0xffb77c44};int c=colors[Library.family(entry.name)];
            TextView badge=bold(Library.extension(entry.name).toUpperCase(Locale.ROOT),10,c);badge.setGravity(Gravity.CENTER);badge.setBackground(shape(night?0xff30463b:0xfff0f2eb,12));r.addView(badge,new LinearLayout.LayoutParams(dp(48),dp(52)));
            LinearLayout info=vertical();pad(info,12,0);TextView n=bold(entry.name,14,fg());n.setMaxLines(2);n.setEllipsize(TextUtils.TruncateAt.END);info.addView(n);space(info,4);
            String date=new SimpleDateFormat("d MMM",new Locale("ru")).format(new Date(entry.time));
            info.addView(text(Library.size(entry.size)+"  ·  "+date+(entry.bookmarks.length()>0?"  ·  ★":""),11,MUTED));r.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            r.addView(text("›",24,MUTED));r.setOnClickListener(v->openCopy(entry));
            r.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle(entry.name).setMessage("Удалить локальную копию и запись из недавних? Исходный файл сохранится.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{library.remove(entry);renderRecents();}).show();return true;});
            recentList.addView(r);space(recentList,8);count++;
        }
        if(count==0){TextView empty=text(filenameQuery.isEmpty()&&filter==0?"Здесь появятся открытые документы.\nВыберите первый файл кнопкой выше.":"Подходящих файлов пока нет.",14,MUTED);pad(empty,10,24);empty.setGravity(Gravity.CENTER);recentList.addView(empty);}
    }
    private void homeMenu() {
        new AlertDialog.Builder(this).setTitle("UniReader").setItems(new String[]{night?"Светлая тема":"Тёмная тема","Поддерживаемые форматы","О приложении и лицензии"},(d,w)->{
            if(w==0){night=!night;library.prefs.edit().putBoolean("night",night).apply();home();}
            if(w==1)formats();if(w==2)about();
        }).show();
    }
    private void formats() {new AlertDialog.Builder(this).setTitle("Форматы").setMessage("PDF\nWord: DOC, DOCX, DOCM, DOT, DOTX, DOTM, RTF\nWriter: ODT, OTT, FODT, SXW, STW\nExcel: XLS, XLSX, XLSM, XLSB, XLT, XLTX, XLTM\nCalc: ODS, OTS, FODS, SXC, STC, CSV, TSV\nPowerPoint: PPT, PPTX, PPTM, PPS, PPSX, PPSM, POT, POTX, POTM\nImpress: ODP, OTP, FODP, SXI, STI\nDraw: ODG, OTG, FODG, SXD, STD\nОбычный текст: TXT\n\nПоддержка зависит от фильтров LibreOffice. Возможны отличия шрифтов и вёрстки. Макросы не запускаются. Парольные документы пока недоступны.").setPositiveButton("Понятно",null).show();}
    private void about() {new AlertDialog.Builder(this).setTitle("UniReader 1.2.1").setMessage("Просмотр, базовое редактирование и конвертация без рекламы, аналитики и доступа к интернету. Документы хранятся в закрытой папке приложения. Удаление приложения удаляет локальные копии и закладки.\n\nДвижок: LibreOfficeKit 26.2.6.3, The Document Foundation и участники LibreOffice, MPL 2.0 и лицензии компонентов. PDF: MuPDF 1.28.5, Artifex Software, AGPL 3.0+. Исходники приложения распространяются под AGPL 3.0+; отдельные компоненты сохраняют свои лицензии.\n\nИсходники движка:\nhttps://download.documentfoundation.org/libreoffice/src/26.2.6/\nhttps://f-droid.org/repo/org.documentfoundation.libreoffice_131_src.tar.gz\n\nЭто самостоятельный просмотрщик, не официальный продукт LibreOffice.").setPositiveButton("Закрыть",null).setNeutralButton("Лицензии",(d,w)->{
        try(InputStream in=getAssets().open("NOTICE-QuietRead.txt")) {ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);new AlertDialog.Builder(this).setTitle("Лицензии").setMessage(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)).setPositiveButton("Закрыть",null).show();}catch(IOException ex){error(ex.getMessage());}
    }).show();}
    private void pick() {
        if(loading)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,OPEN);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==ExportController.CREATE){if(exporter!=null)exporter.result(result,data);return;}
        if(request==OPEN && result==RESULT_OK && data!=null && data.getData()!=null) {
            try{if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
            importDocument(data.getData());
        }
    }
    private void loading(String name) {
        base();LinearLayout box=vertical();box.setGravity(Gravity.CENTER);pad(box,32,24);root.addView(box,new LinearLayout.LayoutParams(-1,0,1));
        ProgressBar spinner=new ProgressBar(this);box.addView(spinner,new LinearLayout.LayoutParams(dp(40),dp(40)));space(box,24);
        box.addView(bold("Открываем документ",22,fg()));space(box,12);TextView file=text(name,14,MUTED);file.setGravity(Gravity.CENTER);box.addView(file);space(box,16);
        TextView hint=text("При первом запуске готовим шрифты и движок.\nБольшие документы могут открываться дольше.",12,MUTED);hint.setGravity(Gravity.CENTER);box.addView(hint);space(box,24);box.addView(button("Отмена",this::leave));
    }
    private void importDocument(Uri uri) {
        if(exporter!=null&&exporter.busy){toast("Дождитесь завершения экспорта");return;}
        if(editor!=null&&editor.confirmClose(()->importDocument(uri)))return;
        leaveEngine();loading=true;final int id=++generation;loading("Читаем файл…");
        ReaderEngine.WORKER.execute(()->{
            try {Library.Item value=library.importUri(uri);main.post(()->{if(id==generation&&!destroyed)openCopy(value);});}
            catch(Exception ex){main.post(()->{if(id==generation&&!destroyed){loading=false;home();error(ex.getMessage());}});}
        });
    }
    private void openCopy(Library.Item value) {
        leaveEngine();loading=true;item=value;final int id=++generation;loading(value.name);loadStarted=SystemClock.elapsedRealtime();
        ReaderEngine.WORKER.execute(()->{
            ReaderEngine opened=null;
            try {
                File file=value.draftPath!=null&&new File(value.draftPath).isFile()?new File(value.draftPath):new File(value.path);
                if(!file.exists())throw new IOException("Локальная копия недоступна. Выберите исходный файл заново.");
                ReaderEngine.Callback callback=(code,payload)->main.post(()->{if(id==generation&&!destroyed)onEngineMessage(code,payload);});
                // Upgrade 1.1's Draw drafts without discarding their edits or touching the original PDF.
                if(Library.extension(value.name).equals("pdf")&&Library.extension(file.getName()).equals("odg")){
                    File upgraded=File.createTempFile("pdf-migration-",".pdf",library.directory);ReaderEngine legacy=null;
                    try{legacy=ReaderEngine.openOffice(this,file,callback);legacy.export(upgraded,"pdf","");}
                    catch(Exception ex){upgraded.delete();throw ex;}finally{if(legacy!=null)legacy.close();}
                    try{opened=ReaderEngine.open(this,upgraded,callback);}catch(Exception ex){upgraded.delete();throw ex;}
                    value.draftPath=upgraded.getAbsolutePath();library.save(value);file.delete();file=upgraded;
                }
                boolean qaPdf=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("qa_office_pdf",false);
                if(opened==null)opened=qaPdf?ReaderEngine.openOffice(this,file,callback):ReaderEngine.open(this,file,callback);
                opened.setPart(value.part);
                ReaderEngine ready=opened;
                main.post(()->{
                    if(id!=generation||destroyed){ReaderEngine.WORKER.execute(ready::close);return;}
                    engine=ready;loading=false;value.time=System.currentTimeMillis();library.save(value);
                    controllers(ready,value);reader();
                    android.util.Log.i("QuietRead","OPEN_OK "+value.name+" type="+ready.type+" parts="+ready.parts+" size="+ready.width+"x"+ready.height+" ms="+(SystemClock.elapsedRealtime()-loadStarted));
                    if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)try{Class.forName("app.quietread.QaExports").getMethod("run",ReaderEngine.class,Intent.class,File.class).invoke(null,ready,getIntent(),getFilesDir());}catch(ReflectiveOperationException ignored){}
                });
            } catch(Exception | LinkageError ex) {
                if(opened!=null)opened.close();
                android.util.Log.e("QuietRead","OPEN_FAILED "+value.name,ex);
                main.post(()->{if(id==generation&&!destroyed){loading=false;home();error(ex.getMessage());}});
            }
        });
    }
    private void controllers(ReaderEngine ready,Library.Item value){
        exporter=new ExportController(this,ready,value.name,this::error);
        editor=new EditorController(this,ready,value,library,new EditorController.Host(){
            public void rebuild(){savePosition();reader();}public void changed(){updateEditorStatus();updateLocation();}
            public void error(String message){MainActivity.this.error(message);}public void export(){exportDocument();}public void editPdf(){MainActivity.this.editPdf(null);}
        });
    }
    private void editPdf(Runnable after){
        if(editor==null||engine==null||engine.pdf==null)return;
        editor.editing=true;savePosition();reader();if(after!=null)after.run();
    }
    private void reader() {
        if(documentView!=null)documentView.detach();
        base();LinearLayout bar=row();pad(bar,12,8);bar.addView(iconButton("back","Закрыть документ",this::leave));
        LinearLayout name=vertical();pad(name,12,0);title=bold(item.name,15,fg());title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.MIDDLE);name.addView(title);
        name.addView(text(Library.extension(item.name).toUpperCase(Locale.ROOT)+"  ·  "+Library.size(item.size)+"  ·  "+(item.draftPath==null?"Копия":"Черновик"),11,MUTED));bar.addView(name,new LinearLayout.LayoutParams(0,-2,1));bar.addView(iconButton("more","Действия с документом",this::readerMenu));root.addView(bar);
        if(editor!=null&&editor.editing){
            editStatus=text("",12,accent());pad(editStatus,18,4);root.addView(editStatus);root.addView(editor.toolbar());updateEditorStatus();
        }else editStatus=null;
        documentView=new DocumentView(this);documentView.setNight(night);root.addView(documentView,new LinearLayout.LayoutParams(-1,0,1));
        if(editor!=null)editor.bind(documentView);
        documentView.listener=new DocumentView.Listener(){
            @Override public void position(float x,float y,float zoom){if(item!=null){item.x=x;item.y=y;item.zoom=zoom;updateLocation();}}
            @Override public void renderError(String msg){toast(msg);}
        };
        LinearLayout controls=row();pad(controls,10,6);
        controls.addView(iconButton("back","Предыдущая страница или часть",()->step(-1)));
        location=bold("",12,fg());location.setGravity(Gravity.CENTER);location.setMaxLines(2);location.setOnClickListener(v->parts());location.setContentDescription("Перейти к странице, листу или слайду");controls.addView(location,new LinearLayout.LayoutParams(0,dp(48),1));
        controls.addView(iconButton("next","Следующая страница или часть",()->step(1)));
        View minus=iconButton("minus","Уменьшить масштаб",()->documentView.zoomBy(.8f));LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(dp(44),dp(48));gap.setMarginStart(dp(8));controls.addView(minus,gap);
        controls.addView(iconButton("plus","Увеличить масштаб",()->documentView.zoomBy(1.25f)));
        root.addView(controls);
        final float x=item.x,y=item.y,z=item.zoom;
        documentView.post(()->{if(documentView!=null && engine!=null)documentView.attach(engine,x,y,z);});updateLocation();
    }
    private int writerPage(){if(engine==null)return 0;int page=0;float y=documentView==null?0:documentView.docY()+documentView.getHeight()/(2f*documentView.zoomValue());for(int i=0;i<engine.pages.size();i++)if(engine.pages.get(i).top<=y)page=i;return page;}
    private void updateLocation(){
        if(engine==null||location==null)return;
        if(engine.type==0 && !engine.pages.isEmpty())location.setText("Стр. "+(writerPage()+1)+" / "+engine.pages.size());
        else if(engine.type==0)location.setText("Документ");
        else location.setText((engine.type==1?engine.names[engine.part]:engine.type==2?"Слайд "+(engine.part+1):"Страница "+(engine.part+1))+" / "+engine.parts);
    }
    private void parts(){
        if(engine==null)return;
        if(engine.type==0){
            if(engine.pages.isEmpty()){toast("Разметка страниц пока недоступна");return;}
            String[] labels=new String[engine.pages.size()];for(int i=0;i<labels.length;i++)labels[i]="Страница "+(i+1);
            new AlertDialog.Builder(this).setTitle("Перейти к странице").setItems(labels,(d,w)->documentView.jump(0,engine.pages.get(w).top,null)).show();
        }else{
            String[] labels=new String[engine.parts];for(int i=0;i<labels.length;i++)labels[i]=(i+1)+". "+(engine.names[i]==null||engine.names[i].isEmpty()?"Часть "+(i+1):engine.names[i]);
            new AlertDialog.Builder(this).setTitle(engine.type==1?"Листы":"Страницы / слайды").setItems(labels,(d,w)->changePart(w,0,0,0)).show();
        }
    }
    private void step(int direction){
        if(engine==null)return;
        if(engine.type==0){int page=writerPage()+direction;if(page>=0&&page<engine.pages.size())documentView.jump(0,engine.pages.get(page).top,null);}
        else {int p=engine.part+direction;if(p>=0&&p<engine.parts)changePart(p,0,0,0);}
    }
    private void changePart(int part,float x,float y,float zoom){
        ReaderEngine current=engine;if(current==null)return;documentView.detach();final int request=++partRequest;
        ReaderEngine.WORKER.execute(()->{try{current.setPart(part);main.post(()->{if(engine==current&&!destroyed&&request==partRequest){item.part=current.part;documentView.attach(current,x,y,zoom);library.save(item);updateLocation();}});}catch(Exception ex){main.post(()->{if(engine==current)error(ex.getMessage());});}});
    }
    private void readerMenu(){
        if(engine==null)return;
        new AlertDialog.Builder(this).setTitle("Документ").setItems(new String[]{editor!=null&&editor.editing?"Режим просмотра":"Редактировать","Сохранить / конвертировать","Найти в документе","Добавить закладку","Закладки", "По ширине / сбросить масштаб",night?"Светлая тема":"Ночной режим","Открыть другой файл","Обновить из исходного файла","Поддерживаемые форматы"},(d,w)->{
            if(w==0){if(editor!=null){editor.toggle();if(editor.editing)toast(editor.hint());}}if(w==1)exportDocument();
            if(w==2)search();if(w==3)addBookmark();if(w==4)bookmarks();if(w==5){documentView.fit();}if(w==6){night=!night;library.prefs.edit().putBoolean("night",night).apply();savePosition();reader();}if(w==7)pick();if(w==8)originalVersion();if(w==9)formats();
        }).show();
    }
    private void exportDocument(){if(engine==null||exporter==null)return;if(editor!=null)editor.commitInput();exporter.choose(writerPage());}
    private void updateEditorStatus(){if(editor==null)return;if(title!=null)title.setText(item.name+(editor.dirty?" *":""));if(editStatus!=null)editStatus.setText(editor.status()+" · "+editor.hint());}
    private void originalVersion(){if(editor!=null&&editor.confirmClose(this::originalVersion))return;if(item.draftPath!=null)new AlertDialog.Builder(this).setTitle("Открыть исходную версию?").setMessage("Локальный черновик будет удалён. Если он нужен, сначала сохраните его копию через экспорт.").setNegativeButton("Отмена",null).setPositiveButton("Открыть оригинал",(d,w)->{library.removeDraft(item);library.save(item);importDocument(Uri.parse(item.uri));}).show();else importDocument(Uri.parse(item.uri));}
    private void addBookmark(){
        if(item==null||engine==null)return;savePosition();
        try{String label=engine.type==0?"Страница "+(writerPage()+1):engine.type==1?engine.names[engine.part]:"Страница / слайд "+(engine.part+1);
            item.bookmarks.put(new JSONObject().put("label",label).put("part",engine.part).put("x",item.x).put("y",item.y).put("zoom",item.zoom));library.save(item);toast("Закладка сохранена");
        }catch(JSONException ex){error(ex.getMessage());}
    }
    private void bookmarks(){
        if(item.bookmarks.length()==0){toast("Закладок пока нет");return;}
        String[] labels=new String[item.bookmarks.length()];for(int i=0;i<labels.length;i++)labels[i]=item.bookmarks.optJSONObject(i).optString("label")+"  ★";
        new AlertDialog.Builder(this).setTitle("Закладки").setItems(labels,(d,w)->{JSONObject b=item.bookmarks.optJSONObject(w);changePart(b.optInt("part"),(float)b.optDouble("x"),(float)b.optDouble("y"),(float)b.optDouble("zoom"));}).setNeutralButton("Удалить все",(d,w)->{item.bookmarks=new JSONArray();library.save(item);toast("Закладки удалены");}).show();
    }
    private void search(){
        if(engine==null)return;
        EditText query=new EditText(this);query.setSingleLine();query.setHint("Слово или фраза");pad(query,20,8);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Найти в документе").setView(query).setPositiveButton("Далее",null).setNeutralButton("Назад",null).setNegativeButton("Закрыть",null).create();
        dialog.setOnShowListener(d->{Keyboard.show(query,dialog);dialog.getButton(-1).setOnClickListener(v->find(query.getText().toString(),false));dialog.getButton(-3).setOnClickListener(v->find(query.getText().toString(),true));});dialog.show();
    }
    private void find(String query,boolean backward){if(query.trim().isEmpty()||engine==null)return;ReaderEngine e=engine;ReaderEngine.WORKER.execute(()->{try{e.search(query,backward);}catch(Exception ex){main.post(()->error(ex.getMessage()));}});}
    private void onEngineMessage(int code,String payload){
        if(engine==null||documentView==null)return;
        if(code==12)toast("Совпадений не найдено");
        if(code==0&&editor!=null&&editor.editing){main.removeCallbacks(repaint);main.postDelayed(repaint,120);}
        if(editor!=null){if(code==19)editor.cellSelection(null,payload);if(code==34)editor.cellSelection(payload,null);}
        if((code==1||code==17)&&editor!=null&&editor.editing&&payload!=null){try{String[] v=payload.split(",\\s*");if(v.length>=4){float x=Float.parseFloat(v[0]),y=Float.parseFloat(v[1]);documentView.setCursor(new RectF(x,y,x+Math.max(15,Float.parseFloat(v[2])),y+Float.parseFloat(v[3])));if(code==17&&(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)android.util.Log.i("UniReaderQA","CELL_CURSOR "+payload);}}catch(Exception ignored){}}
        if(code==15){try{
            JSONObject json=new JSONObject(payload);JSONArray results=json.optJSONArray("searchResultSelection");
            if(results!=null&&results.length()>0){JSONObject found=results.getJSONObject(0);String[] v=found.getString("rectangles").split(";")[0].split(",\\s*");float x=Float.parseFloat(v[0]),y=Float.parseFloat(v[1]);RectF rect=new RectF(x,y,x+Float.parseFloat(v[2]),y+Float.parseFloat(v[3]));int part=found.optInt("part",engine.part);if(part!=engine.part)changePart(part,x,y,documentView.zoomValue());else documentView.jump(x,y,rect);}
        }catch(Exception ignored){toast("Совпадение найдено");}}
    }
    private void savePosition(){if(engine!=null&&item!=null&&documentView!=null){item.x=documentView.docX();item.y=documentView.docY();item.zoom=documentView.zoomValue();item.part=engine.part;library.save(item);}}
    private final Runnable repaint=()->{if(documentView!=null)documentView.refresh();};
    private void leaveEngine(){savePosition();main.removeCallbacks(repaint);if(editor!=null){editor.close();editor=null;}if(exporter!=null){exporter.close();exporter=null;}if(documentView!=null){documentView.detach();documentView=null;}ReaderEngine old=engine;engine=null;location=null;if(old!=null)ReaderEngine.WORKER.execute(old::close);}
    private void leave(){if(exporter!=null&&exporter.busy){toast("Дождитесь завершения сохранения");return;}if(editor!=null)editor.commitInput();if(editor!=null&&editor.confirmClose(this::leave))return;generation++;loading=false;leaveEngine();item=null;home();}
    @Override public void onBackPressed(){if(engine!=null||loading)leave();else super.onBackPressed();}
    @Override protected void onPause(){savePosition();if(editor!=null){editor.commitInput();if(editor.dirty&&!editor.saving)editor.saveDraft(null);}super.onPause();}
    @Override protected void onDestroy(){destroyed=true;generation++;if(pdfProgress!=null)pdfProgress.dismiss();if(errorDialog!=null)errorDialog.dismiss();leaveEngine();super.onDestroy();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    private void error(String message){if(!destroyed){if(errorDialog!=null)errorDialog.dismiss();errorDialog=new AlertDialog.Builder(this).setTitle("Не удалось открыть").setMessage(message==null?"Неизвестная ошибка":message).setPositiveButton("Понятно",null).show();}}
    private long lastToast;
    private void toast(String text){long now=SystemClock.uptimeMillis();if(now-lastToast>1200){lastToast=now;Toast.makeText(this,text,Toast.LENGTH_LONG).show();}}

    private static final class Glyph extends View {
        final String symbol;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final Path path=new Path();final int color;
        Glyph(Context c,String s,int color){super(c);symbol=s;this.color=color;setFocusable(true);setClickable(true);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();c.translate(getWidth()/2f,getHeight()/2f);float unit=Math.min(getWidth(),getHeight())/48f;c.scale(unit,unit);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(symbol.equals("back")||symbol.equals("next")){float sign=symbol.equals("back")?1:-1;path.reset();path.moveTo(3*sign,-7);path.lineTo(-4*sign,0);path.lineTo(3*sign,7);c.drawPath(path,p);}
            if(symbol.equals("plus")||symbol.equals("minus")){c.drawLine(-6,0,6,0,p);if(symbol.equals("plus"))c.drawLine(0,-6,0,6,p);}
            if(symbol.equals("more")){p.setStyle(Paint.Style.FILL);c.drawCircle(0,-7,1.7f,p);c.drawCircle(0,0,1.7f,p);c.drawCircle(0,7,1.7f,p);}
            if(symbol.equals("file")){path.reset();path.moveTo(-9,-14);path.lineTo(3,-14);path.lineTo(10,-7);path.lineTo(10,14);path.lineTo(-9,14);path.close();c.drawPath(path,p);c.drawLine(-4,1,5,1,p);c.drawLine(-4,7,3,7,p);}c.restore();}
    }
}
