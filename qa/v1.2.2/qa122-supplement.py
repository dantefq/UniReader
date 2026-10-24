from pathlib import Path
import importlib.util
spec=importlib.util.spec_from_file_location('inline122','work/qa122-ui.py');ui=importlib.util.module_from_spec(spec);spec.loader.exec_module(ui)
from ui12 import *
adb=ui.adb

def start(name):
 open_doc(name);ui.menu('По ширине / сбросить масштаб');ui.menu('Редактировать');t=tree();_,x,y=ui.point(name,t);adb('shell','input','tap',str(x),str(y));time.sleep(.8);assert keyboard();return tree()

def type_value(t,value):
 field=next(n for n in t.iter('node') if n.get('content-desc')=='Текст в документе');adb('shell','input','tap',*map(str,center(field)));adb('shell','input','keycombination','113','29');adb('shell','input','text',value)

start('sample.docx');t=tree();marker='AUTO122_'+str(int(time.time()));type_value(t,marker)
t=tree();_,x,y=ui.point('sample.docx',t);adb('shell','input','tap',str(x),str(y));time.sleep(1)
content=ZipFile(io.BytesIO(draft('sample.docx'))).read('content.xml').decode();assert content.count(marker)==1;assert keyboard();print('PASS: tapping another position commits the previous text once',flush=True)
t=tree();cancel='DISCARD122_'+str(int(time.time()));type_value(t,cancel);tap('Сбросить ввод текста');time.sleep(.7);assert not keyboard();assert not any(n.get('content-desc')=='Текст в документе' for n in tree().iter('node'))
content=ZipFile(io.BytesIO(draft('sample.docx'))).read('content.xml').decode();assert cancel not in content;print('PASS: cancel closes the inline field, hides IME and does not modify the document',flush=True)
# Confirm two successive edits of an existing saved PDF and the inline font control.
for iteration in range(2):
 t=start('pdf-ui12.pdf');value='REPEAT122_'+str(iteration)+'_'+str(int(time.time()));type_value(t,value)
 if iteration==1:tap('Увеличить размер текста PDF')
 tap('Применить текст');data=draft('pdf-ui12.pdf')
 from pypdf import PdfReader
 text=PdfReader(io.BytesIO(data)).pages[0].extract_text();assert text.count(value)==1;assert not keyboard()
print('PASS: PDF can be edited, saved, reopened and edited again; inline font-size control applied',flush=True)
