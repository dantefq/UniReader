import subprocess,time,re,json,io,xml.etree.ElementTree as ET
from zipfile import ZipFile
from pathlib import Path
ADB=r'C:\Users\dante\AppData\Local\Android\Sdk\platform-tools\adb.exe'
OUT=Path('work/ui12');OUT.mkdir(exist_ok=True)
def adb(*a):return subprocess.run([ADB,'-s','emulator-5554',*a],capture_output=True,check=True).stdout
def tree():
 adb('shell','uiautomator','dump','/sdcard/uni-ui.xml');return ET.fromstring(adb('shell','cat','/sdcard/uni-ui.xml'))
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def center(n):
 b=bounds(n);return [(b[0]+b[2])//2,(b[1]+b[3])//2]
def tap(label):
 for attempt in range(7):
  t=tree()
  for n in t.iter('node'):
   if label.casefold() in [n.get('text','').casefold(),n.get('content-desc','').casefold()]:
    adb('shell','input','tap',*map(str,center(n)));time.sleep(.25);return
  scroll=[n for n in t.iter('node') if n.get('class')=='android.widget.HorizontalScrollView']
  if not scroll:break
  b=bounds(scroll[0]);y=(b[1]+b[3])//2;adb('shell','input','swipe',str(b[0]+(b[2]-b[0])*3//4),str(y),str(b[0]+(b[2]-b[0])//4),str(y),'250')
 raise AssertionError('Missing '+label+' '+str([(n.get('text'),n.get('content-desc')) for n in t.iter('node')]))
def input_text(value):
 n=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText');adb('shell','input','tap',*map(str,center(n)));adb('shell','input','keycombination','113','29');adb('shell','input','text',value)
def menu(label):tap('Действия с документом');tap(label)
def open_doc(name):adb('shell','am','start','-f','0x20000000','-n','app.quietread/.MainActivity','--es','qa_fixture',name);time.sleep(2)
def screenshot(name):(OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
def keyboard():
 state=adb('shell','dumpsys','input_method').decode(errors='replace');(OUT/'ime.txt').write_text(state,encoding='utf-8');return 'mInputShown=true' in state
def draft(name):
 time.sleep(5);root=ET.fromstring(adb('exec-out','run-as','app.quietread','cat','shared_prefs/library.xml'));items=json.loads(next(n.text for n in root if n.get('name')=='items'));entry=max((i for i in items if i['name']==name and i.get('draftPath')),key=lambda i:i['time']);data=adb('exec-out','run-as','app.quietread','cat',entry['draftPath']);(OUT/(name+'.draft')).write_bytes(data);return data
if __name__=='__main__':
 open_doc('wide12.xlsx');menu('Редактировать');tap('Адрес ячейки');input_text('B2');tap('Перейти');time.sleep(.7);assert keyboard(),'IME did not open';screenshot('cell-keyboard');input_text('1001');tap('Применить значение ячейки');assert not keyboard();assert b'1001' in ZipFile(io.BytesIO(draft('wide12.xlsx'))).read('content.xml');print('Cell value + automatic keyboard PASS',flush=True)
 tap('Адрес ячейки');input_text('C2');tap('Перейти');input_text('=B2+399');tap('Применить значение ячейки');content=ZipFile(io.BytesIO(draft('wide12.xlsx'))).read('content.xml');assert b'1400' in content and b'B2' in content;print('Formula and calculation PASS',flush=True)
 # Tap the selected cell using its native cursor rectangle and the canvas viewport.
 log=adb('logcat','-d','-s','UniReaderQA').decode();rect=list(map(int,re.findall(r'CELL_CURSOR (\d+), (\d+), (\d+), (\d+)',log)[-1]));node=next(n for n in tree().iter('node') if n.get('content-desc','').startswith('Документ.'));b=bounds(node);scale=2.625/15
 x=round(b[0]+32+(rect[0]+rect[2]/2)*scale);y=round(b[1]+32+(rect[1]+rect[3]/2)*scale);adb('shell','input','tap',str(x),str(y));time.sleep(1);assert keyboard();screenshot('tap-cell-keyboard');adb('shell','input','keyevent','4');print('Canvas cell tap opens keyboard PASS',flush=True)
 menu('Ночной режим');screenshot('dark-cell');menu('Сохранить / конвертировать');screenshot('dark-menu');adb('shell','input','keyevent','4');print('Dark theme screenshots captured',flush=True)
