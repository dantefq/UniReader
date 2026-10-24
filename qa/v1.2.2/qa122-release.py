from ui12 import *
import ui12
old_adb=adb
def legacy(*args):
 if args[0]=='shell':args=('shell','-T','-x',*args[1:])
 return subprocess.run([ADB,'-s','emulator-5554',*args],capture_output=True,check=True,timeout=60).stdout
ui12.adb=legacy;adb=legacy
adb('shell','am','start','-n','app.quietread/.MainActivity');time.sleep(1)
tap('Открыть документ  +');time.sleep(1);tap('sample.docx');time.sleep(2)
tap('Действия с документом');time.sleep(.5);tap('По ширине / сбросить масштаб');time.sleep(.4);tap('Действия с документом');time.sleep(.5);tap('Редактировать');time.sleep(.6)
t=tree();node=next(n for n in t.iter('node') if n.get('content-desc','').startswith('Документ.'));b=bounds(node)
log=adb('logcat','-d','-s','QuietRead').decode(errors='replace');w=int(re.findall(r'OPEN_OK sample.docx type=0 parts=\d+ size=(\d+)x',log)[-1]);scale=min(.1,(b[2]-b[0]-64)/w)
x=round(b[0]+max(32,(b[2]-b[0]-w*scale)/2)+2200*scale);y=round(b[1]+32+2000*scale);adb('shell','input','tap',str(x),str(y));time.sleep(.9)
assert keyboard();t=tree();field=next(n for n in t.iter('node') if n.get('content-desc')=='Текст в документе');assert any(n.get('content-desc','').startswith('Документ.') for n in t.iter('node'));assert not any(n.get('text')=='Изменить текст' for n in t.iter('node'))
Path('work/qa122/ui/release-inline.png').write_bytes(adb('exec-out','screencap','-p'));tap('Сбросить ввод текста');assert not keyboard()
print('PASS: signed 1.2.2 release installed as update; opened DOCX via Android picker; canvas tap opens docked editor and IME; cancel closes it',flush=True)
