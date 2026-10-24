import sys,subprocess,time,io,json
from pathlib import Path
from zipfile import ZipFile
ADB=r'C:/Users/dante/AppData/Local/Android/Sdk/platform-tools/adb.exe'
def adb(*args):
 if args[0]=='shell':args=('shell','-T','-x',*args[1:])
 return subprocess.run([ADB,'-s','emulator-5554',*args],capture_output=True,timeout=45,check=True).stdout
out=Path('work/qa122/native');out.mkdir(parents=True,exist_ok=True)
for name in sys.argv[1:]:
 subprocess.run([ADB,'-s','emulator-5554','exec-out','run-as','app.quietread','rm','-f','files/qa122/'+name+'/PASS','files/qa122/'+name+'/FAIL.txt'],capture_output=True,timeout=20)
 adb('shell','am','start','-f','0x20000000','-n','app.quietread/.MainActivity','--es','qa_fixture',name,'--ez','qa_unicode122','true')
 for i in range(90):
  time.sleep(.7);state=adb('exec-out','run-as','app.quietread','ls','files/qa122/'+name).decode(errors='replace')
  if 'FAIL.txt' in state:
   print(adb('exec-out','run-as','app.quietread','cat','files/qa122/'+name+'/FAIL.txt').decode(),flush=True);raise RuntimeError(name)
  if 'PASS' in state:break
 else:raise RuntimeError('timeout '+name)
 dest=out/name;dest.mkdir(exist_ok=True)
 files=adb('exec-out','run-as','app.quietread','ls','files/qa122/'+name).decode().split()
 for f in files:(dest/f).write_bytes(adb('exec-out','run-as','app.quietread','cat','files/qa122/'+name+'/'+f))
 expected=(dest/'expected.txt').read_text(encoding='utf8')
 edited=next(dest.glob('edited.*'))
 if edited.suffix=='.pdf':
  from pypdf import PdfReader
  text=PdfReader(edited).pages[0].extract_text()
 else:text=ZipFile(edited).read('content.xml').decode('utf8')
 assert expected in text,(name,text[:800]);assert '\ufffd' not in text,name
 if (dest/'selection.txt').exists():
  selection=(dest/'selection.txt').read_text(encoding='utf8');assert name not in ['sample.docx','sample.xlsx'] or expected in selection,(name,selection)
 if (dest/'legacy.odt').exists():
  before=ZipFile(dest/'legacy.odt').read('content.xml').decode('utf8');assert expected not in before;print('Reproduced old UTF-8 truncation',repr(before[before.find('Привет'):before.find('Привет')+90]),flush=True)
 if (dest/'formula.ods').exists():
  formula=ZipFile(dest/'formula.ods').read('content.xml').decode('utf8');assert 'Привет 😀' in formula and 'table:formula=' in formula
 print(json.dumps({'file':name,'unicode_roundtrip':True,'selection_utf8':(dest/'selection.txt').exists() and expected in (dest/'selection.txt').read_text(encoding='utf8'),'formula_unicode':(dest/'formula.ods').exists()},ensure_ascii=False),flush=True)
