package app.quietread;

import org.libreoffice.kit.Document;
import java.nio.charset.StandardCharsets;

/** Uses explicit byte lengths, including supplementary Unicode characters. */
final class Utf8Bridge {
    static { System.loadLibrary("unireader-text"); }
    static native boolean paste(Document document,byte[] utf8);
    private static native byte[] selection(Document document);
    static boolean paste(Document document,String value){
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw new IllegalArgumentException("Неполный символ Unicode. Введите его заново.");}
            else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Неполный символ Unicode. Введите его заново.");
        }
        return paste(document,value.getBytes(StandardCharsets.UTF_8));
    }
    static String selectedText(Document document){byte[] bytes=selection(document);return bytes==null?"":new String(bytes,StandardCharsets.UTF_8);}
}
