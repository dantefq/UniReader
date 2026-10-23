package app.quietread;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.text.InputType;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;

/** Basic native Office editing. Private ODF drafts preserve the source file. */
final class EditorController {
    interface Host { void rebuild(); void changed(); void error(String message); void export(); void editPdf(); }
    final Activity activity;final ReaderEngine engine;final Library.Item item;final Library library;final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private DocumentView view;
    boolean editing,dirty,closed,saving;
    private int revision;
    String cellFormula="",cellAddress="A1";
    private EditText cellInput;
    private Button cellName;
    private boolean cellTyping,updatingCell;
    private int cellTap;
    private int textTap;
    private boolean textOpening;
    private AlertDialog textDialog;
    private String pdfTool="replace";
    private final Runnable autosave=()->saveDraft(null);
    EditorController(Activity a,ReaderEngine e,Library.Item i,Library l,Host h){activity=a;engine=e;item=i;library=l;host=h;}
    void bind(DocumentView v){view=v;v.setEditing(editing,(x,y,count,zoom)->{
        if(engine.pdf!=null){pdfTap(x/20f,y/20f);return;}
        if(engine.type==1){if(cellTyping)applyCell(false);cellTyping=false;int request=++cellTap;
            ReaderEngine.WORKER.execute(()->{if(closed)return;engine.click(x,y,1,zoom);main.postDelayed(()->{if(!closed&&request==cellTap){syncCell();focusCell();}},140);});
        }else {
            int request=++textTap;
            ReaderEngine.WORKER.execute(()->{try{if(closed)return;
                // Draw/Impress require entering the text object, rather than only selecting its frame.
                engine.click(x,y,engine.type==2||engine.type==3?2:count,zoom);
                main.postDelayed(()->{if(!closed&&editing&&view==v&&request==textTap)text();},180);
            }catch(RuntimeException ex){main.post(()->{if(!closed)host.error(ex.getMessage());});}});
        }
    });}
    void toggle(){commitInput();editing=!editing;host.rebuild();}
    View toolbar(){
        if(engine.pdf!=null)return pdfToolbar();
        LinearLayout container=new LinearLayout(activity);container.setOrientation(LinearLayout.VERTICAL);
        if(engine.type==1){LinearLayout formula=new LinearLayout(activity);formula.setGravity(Gravity.CENTER_VERTICAL);formula.setPadding(dp(10),dp(6),dp(10),dp(6));
            cellName=new Button(activity);cellName.setText(cellAddress);cellName.setContentDescription("Адрес ячейки");cellName.setOnClickListener(v->goToCell());formula.addView(cellName,new LinearLayout.LayoutParams(dp(68),dp(46)));
            cellInput=input("Значение или =формула");cellInput.setContentDescription("Значение или формула ячейки");cellInput.setSingleLine(true);cellInput.setSelectAllOnFocus(true);cellInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);cellInput.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            cellInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){if(!updatingCell)cellTyping=true;}public void afterTextChanged(Editable e){}});
            cellInput.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){applyCell(true);return true;}return false;});
            formula.addView(cellInput,new LinearLayout.LayoutParams(0,dp(48),1));Button apply=new Button(activity);apply.setText("✓");apply.setContentDescription("Применить значение ячейки");apply.setOnClickListener(v->applyCell(true));formula.addView(apply,new LinearLayout.LayoutParams(dp(48),dp(46)));container.addView(formula);syncCell();
        }
        HorizontalScrollView scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);int pad=dp(6);bar.setPadding(pad,pad,pad,pad);scroll.addView(bar);
        add(bar,"Просмотр",()->{commitInput();editing=false;host.rebuild();});
        if(engine.type==1){add(bar,"Отменить",()->{commitInput();command(".uno:Undo",true);});add(bar,"Повторить",()->command(".uno:Redo",true));add(bar,"Оформление",()->{
            String[] labels={"Жирный","Курсив","Подчеркнуть","По левому краю","По центру","По правому краю"},commands={"Bold","Italic","Underline","AlignLeft","AlignHorizontalCenter","AlignRight"};new AlertDialog.Builder(activity).setTitle("Оформление ячейки").setItems(labels,(d,w)->{commitInput();command(".uno:"+commands[w],true);}).show();});add(bar,"Сохранить копию",()->{commitInput();host.export();});container.addView(scroll);return container;}
        if(engine.type==1)add(bar,"Ячейка",this::goToCell);
        else if(engine.type==0){add(bar,"Начало",()->command(".uno:GoToStartOfDoc",false));add(bar,"Конец",()->command(".uno:GoToEndOfDoc",false));}
        if(engine.type!=1)add(bar,"Текст",this::text);
        add(bar,"Выделить всё",()->command(".uno:SelectAll",false));
        add(bar,"Жирный",()->command(".uno:Bold",true));add(bar,"Курсив",()->command(".uno:Italic",true));add(bar,"Подчеркнуть",()->command(".uno:Underline",true));
        add(bar,"Абзац",this::format);
        add(bar,"Отменить",()->command(".uno:Undo",true));add(bar,"Повторить",()->command(".uno:Redo",true));
        if(engine.type==2){add(bar,"Добавить слайд",()->command(".uno:InsertPage",true));add(bar,"Удалить слайд",()->new AlertDialog.Builder(activity).setTitle("Удалить текущий слайд?").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->command(".uno:DeletePage",true)).show());}
        add(bar,"Сохранить копию",()->{if(engine.type==1)applyCell(true);host.export();});container.addView(scroll);return container;
    }
    void commitInput(){if(engine.type==1)applyCell(true);}
    private View pdfToolbar(){HorizontalScrollView scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);LinearLayout bar=new LinearLayout(activity);bar.setPadding(dp(8),dp(6),dp(8),dp(6));scroll.addView(bar);
        add(bar,"Просмотр",()->{editing=false;host.rebuild();});
        add(bar,"Инструмент PDF",()->new AlertDialog.Builder(activity).setTitle("Редактирование PDF").setSingleChoiceItems(new String[]{"Заменить текст — коснитесь строки","Добавить надпись — коснитесь места","Выделить текст — коснитесь строки","Удалить текст — коснитесь строки"},java.util.Arrays.asList("replace","add","highlight","delete").indexOf(pdfTool),(d,w)->{pdfTool=new String[]{"replace","add","highlight","delete"}[w];d.dismiss();host.changed();}).show());
        add(bar,"Отменить",()->mutate(()->engine.pdf.undo()));add(bar,"Повторить",()->mutate(()->engine.pdf.redo()));add(bar,"Сохранить PDF",host::export);return scroll;
    }
    String hint(){if(engine.pdf!=null)return pdfTool.equals("add")?"Коснитесь места для новой надписи":pdfTool.equals("highlight")?"Коснитесь строки для выделения":pdfTool.equals("delete")?"Коснитесь строки для удаления":"Коснитесь строки для замены текста";return engine.type==1?"Коснитесь ячейки · введите значение · ✓":"Коснитесь текста — откроется клавиатура";}
    private void pdfTap(float x,float y){String tool=pdfTool;ReaderEngine.WORKER.execute(()->{try{
        if(closed)return;PdfEngine.Line line=tool.equals("add")?engine.pdf.insertion(x,y):engine.pdf.select(x,y);
        main.post(()->{if(closed)return;if(line==null){host.error("В этом месте нет текстового слоя. На скан можно добавить надпись инструментом «Добавить надпись».");return;}
            if(tool.equals("highlight")){mutate(()->engine.pdf.highlight(line));return;}
            if(tool.equals("delete")){mutate(()->engine.pdf.replace(line,"",line.size,true));return;}
            LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),0,dp(16),0);
            EditText field=input("Текст");field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);field.setMinLines(2);field.setMaxLines(6);field.setText(tool.equals("add")?"":line.text);field.setSelectAllOnFocus(true);box.addView(field);
            EditText size=input("Размер шрифта, пт");size.setContentDescription("Размер шрифта PDF");size.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);size.setSingleLine();size.setText(String.format(java.util.Locale.ROOT,"%.1f",line.size));box.addView(size);
            AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(tool.equals("add")?"Новая надпись":"Заменить строку PDF").setMessage("Остальные объекты страницы сохраняются. Новый текст остаётся в выбранном месте, без перевёрстки соседних блоков.").setView(box).setNegativeButton("Отмена",null).setPositiveButton("Применить",null).create();
            dialog.setOnShowListener(d->{showKeyboard(field,dialog);dialog.getButton(-1).setOnClickListener(v->{float points;try{points=Float.parseFloat(size.getText().toString().replace(',','.'));if(points<4||points>144)throw new NumberFormatException();}catch(NumberFormatException ex){size.setError("От 4 до 144 пт");return;}String value=field.getText().toString();dialog.dismiss();mutate(()->engine.pdf.replace(line,value,points,!tool.equals("add")));});});dialog.show();
        });
    }catch(Exception ex){main.post(()->{if(!closed)host.error(ex.getMessage());});}});}
    private void showKeyboard(EditText field,AlertDialog dialog){Keyboard.show(field,dialog);}
    void cellSelection(String address,String formula){
        if(address!=null&&!address.isEmpty())cellAddress=address;if(formula!=null)cellFormula=formula;
        if(!cellTyping)syncCell();
    }
    private void syncCell(){if(cellInput==null)return;updatingCell=true;cellInput.setText(cellFormula);cellInput.setSelection(cellInput.length());updatingCell=false;if(cellName!=null)cellName.setText(cellAddress);}
    private void focusCell(){if(cellInput==null)return;cellInput.requestFocus();cellInput.selectAll();cellInput.postDelayed(()->{if(!closed)((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(cellInput,InputMethodManager.SHOW_IMPLICIT);},80);}
    private void applyCell(boolean hide){if(cellInput==null)return;String value=cellInput.getText().toString();if(cellTyping){cellFormula=value;cellTyping=false;mutate(()->{if(value.isEmpty())engine.command(".uno:DeleteContents",new JSONObject().put("Flags",prop("string","A")).toString());else engine.paste(value);});}
        if(hide){((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(cellInput.getWindowToken(),0);cellInput.clearFocus();if(view!=null)view.requestFocus();}}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private void add(LinearLayout bar,String label,Runnable action){Button b=new Button(activity);b.setAllCaps(false);b.setText(label);b.setTextSize(12);b.setMinHeight(dp(44));b.setContentDescription(label);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.setMarginEnd(dp(5));bar.addView(b,p);}
    private EditText input(String hint){EditText t=new EditText(activity);t.setHint(hint);t.setTextSize(17);t.setPadding(dp(18),dp(8),dp(18),dp(8));return t;}
    void text(){
        if(!editing||closed||textOpening)return;
        if(engine.type==1){focusCell();return;}
        textOpening=true;
        ReaderEngine.WORKER.execute(()->{
            try{if(closed)return;
            String selected=engine.type==1?cellFormula:engine.document.getTextSelection("text/plain;charset=utf-8");
            String initial=selected==null?"":selected;
            main.post(()->{if(closed||!editing){textOpening=false;return;}EditText field=input(engine.type==1?"Значение или =формула":"Текст для вставки или замены выделения");
                field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                field.setMinLines(3);field.setMaxLines(9);field.setText(initial);field.setSelection(field.length());
                AlertDialog d=new AlertDialog.Builder(activity).setTitle(engine.type==1?"Ячейка "+cellAddress:"Изменить текст").setView(field).setNegativeButton("Отмена",null)
                    .setPositiveButton("Применить",(dialog,which)->{
                        String value=field.getText().toString();
                        if(value.isEmpty()&&initial.isEmpty())return;
                        mutate(()->{if(value.isEmpty()){engine.command(engine.type==1?".uno:DeleteContents":".uno:Delete",engine.type==1?new JSONObject().put("Flags",prop("string","A")).toString():"{}");}else engine.paste(value);});
                    }).create();textDialog=d;d.setOnShowListener(dialog->Keyboard.show(field,d));d.setOnDismissListener(dialog->{textOpening=false;textDialog=null;});d.show();
            });
            }catch(RuntimeException ex){main.post(()->{textOpening=false;if(!closed)host.error(ex.getMessage());});}
        });
    }
    private void goToCell(){EditText field=input("Например B2 или A1:C5");field.setSingleLine();field.setText(cellAddress);field.selectAll();AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Перейти к ячейке").setView(field).setNegativeButton("Отмена",null).setPositiveButton("Перейти",(d,w)->{
        String address=field.getText().toString().trim();
        if(!address.matches("[A-Za-z]{1,3}[1-9][0-9]{0,6}(:[A-Za-z]{1,3}[1-9][0-9]{0,6})?")){host.error("Укажите адрес, например A1 или B2:C10");return;}
        cellAddress=address.toUpperCase(java.util.Locale.ROOT);
        if(cellTyping)applyCell(false);ReaderEngine.WORKER.execute(()->{try{engine.command(".uno:GoToCell",new JSONObject().put("ToPoint",prop("string",cellAddress)).toString());main.postDelayed(()->{if(!closed){syncCell();focusCell();}},140);}catch(Exception ex){main.post(()->host.error(ex.getMessage()));}});
    }).create();dialog.setOnShowListener(d->Keyboard.show(field,dialog));dialog.show();}
    private void format(){String[] labels={"По левому краю","По центру","По правому краю","По ширине","Маркированный список","Нумерованный список"};String[] commands={"LeftPara","CenterPara","RightPara","JustifyPara","DefaultBullet","DefaultNumbering"};new AlertDialog.Builder(activity).setTitle("Формат абзаца").setItems(labels,(d,w)->command(".uno:"+commands[w],true)).show();}
    void command(String name,boolean changes){if(changes)mutate(()->engine.command(name,"{}"));else ReaderEngine.WORKER.execute(()->{if(!closed)engine.command(name,"{}");});}
    interface Edit {void apply() throws Exception;}
    private void mutate(Edit operation){
        dirty=true;revision++;host.changed();main.removeCallbacks(autosave);
        ReaderEngine.WORKER.execute(()->{try{if(closed)return;operation.apply();engine.refreshGeometry();main.post(()->{if(closed)return;if(view!=null)view.refresh();host.changed();main.removeCallbacks(autosave);main.postDelayed(autosave,4000);});}
            catch(Exception ex){main.post(()->{if(!closed)host.error(ex.getMessage());});}});
    }
    void saveDraft(Runnable after){
        main.removeCallbacks(autosave);
        if(closed){if(after!=null)after.run();return;}
        if(saving){main.postDelayed(()->saveDraft(after),250);return;}
        if(!dirty){if(after!=null)after.run();return;}
        final int saved=revision;saving=true;host.changed();
        ReaderEngine.WORKER.execute(()->{
            try{
                String ext=engine.pdf!=null?"pdf":engine.type==1?"ods":engine.type==2?"odp":engine.type==3?"odg":"odt";
                File finalFile=new File(library.directory,new File(item.path).getName()+".draft."+ext);
                File temp=File.createTempFile("draft-","."+ext,library.directory);
                try{engine.export(temp,ext,"");Files.move(temp.toPath(),finalFile.toPath(),StandardCopyOption.REPLACE_EXISTING);}finally{temp.delete();}
                item.draftPath=finalFile.getAbsolutePath();library.save(item);
                main.post(()->{saving=false;if(!closed&&revision==saved)dirty=false;if(!closed)host.changed();if(after!=null&&!closed)after.run();});
            }catch(Exception ex){main.post(()->{saving=false;if(!closed){host.changed();host.error("Не удалось сохранить черновик: "+ex.getMessage());}});}
        });
    }
    boolean confirmClose(Runnable action){
        if(!dirty)return false;
        new AlertDialog.Builder(activity).setTitle("Сохранить изменения?").setMessage("Исходный файл не меняется. Можно сохранить локальный черновик и позже экспортировать его.")
            .setNegativeButton("Отмена",null).setNeutralButton("Не сохранять",(d,w)->{main.removeCallbacks(autosave);dirty=false;action.run();})
            .setPositiveButton("Сохранить черновик",(d,w)->saveDraft(action)).show();return true;
    }
    String status(){return saving?"Сохраняем черновик…":dirty?"Есть изменения":item.draftPath!=null?"Черновик сохранён":"Режим редактирования";}
    void close(){closed=true;textTap++;main.removeCallbacks(autosave);if(textDialog!=null)textDialog.dismiss();textOpening=false;if(view!=null)view.setEditing(false,null);view=null;}
    static JSONObject prop(String type,Object value) throws Exception{return new JSONObject().put("type",type).put("value",value);}
}
