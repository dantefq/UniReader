package app.quietread;

import java.util.Locale;
import java.util.regex.*;

/** PDF Unicode destinations are UTF-16BE strings, not Unicode scalar hex numbers. */
final class PdfUnicodeCMap {
    private static final Pattern ENTRY=Pattern.compile("^<([0-9a-fA-F]+)>\\s+<([0-9a-fA-F]+)>(?:\\s+<([0-9a-fA-F]+)>)?\\s*$");
    private static String utf16(int cp){StringBuilder out=new StringBuilder();for(char c:Character.toChars(cp))out.append(String.format(Locale.ROOT,"%04X",(int)c));return out.toString();}
    static String repair(String source){
        StringBuilder out=new StringBuilder();boolean range=false,chars=false;
        for(String line:source.split("\\n",-1)){
            if(line.contains("beginbfrange"))range=true;if(line.contains("beginbfchar"))chars=true;
            Matcher entry=ENTRY.matcher(line.trim());
            if((range||chars)&&entry.matches()){
                if(range&&entry.group(3)!=null){
                    int first=Integer.parseInt(entry.group(1),16),last=Integer.parseInt(entry.group(2),16),cp=Integer.parseInt(entry.group(3),16);
                    if(last>=first&&last-first<65536&&cp<=0x10ffff&&cp+last-first<=0x10ffff&&cp+last-first>0xffff){
                        StringBuilder fixed=new StringBuilder("<"+entry.group(1)+"> <"+entry.group(2)+"> [");
                        for(int i=first;i<=last;i++)fixed.append('<').append(utf16(cp+i-first)).append('>');line=fixed.append(']').toString();
                    }
                }else if(chars&&entry.group(3)==null&&entry.group(2).length()>4){
                    long cp=Long.parseLong(entry.group(2),16);if(cp>0xffff&&cp<=0x10ffff)line="<"+entry.group(1)+"> <"+utf16((int)cp)+">";
                }
            }
            out.append(line).append('\n');if(line.contains("endbfrange"))range=false;if(line.contains("endbfchar"))chars=false;
        }
        return out.toString();
    }
}
