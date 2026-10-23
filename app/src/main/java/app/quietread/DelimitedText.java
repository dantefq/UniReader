package app.quietread;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Streams CSV/TSV into a temporary flat ODF sheet, avoiding LibreOffice's import dialog. */
final class DelimitedText {
    private static final int MAX_CELLS=1_000_000, MAX_FIELD=1_000_000;
    static File prepare(File source,File cache) throws IOException {
        byte[] probe=new byte[(int)Math.min(65536,source.length())];int length;
        try(InputStream in=new FileInputStream(source)){length=in.read(probe);}
        Charset charset=StandardCharsets.UTF_8;int skip=0;
        if(length>=2 && probe[0]==(byte)0xff && probe[1]==(byte)0xfe){charset=StandardCharsets.UTF_16LE;skip=2;}
        else if(length>=2 && probe[0]==(byte)0xfe && probe[1]==(byte)0xff){charset=StandardCharsets.UTF_16BE;skip=2;}
        else if(length>=3 && probe[0]==(byte)0xef && probe[1]==(byte)0xbb && probe[2]==(byte)0xbf)skip=3;
        else if(length>0){CharsetDecoder decoder=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT);if(decoder.decode(ByteBuffer.wrap(probe,0,length),CharBuffer.allocate(length),false).isError())charset=Charset.forName("windows-1251");}
        String sample=new String(probe,skip,Math.max(0,length-skip),charset);
        char separator=source.getName().toLowerCase(Locale.ROOT).endsWith(".tsv")?'\t':guess(sample);
        File out=new File(cache,"quietread-delimited.fods");
        try(InputStream stream=new FileInputStream(source);PushbackReader reader=new PushbackReader(new BufferedReader(new InputStreamReader(stream,charset)),2);Writer writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out),StandardCharsets.UTF_8))){
            if(skip>0)reader.read();
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" office:version=\"1.2\" office:mimetype=\"application/vnd.oasis.opendocument.spreadsheet\"><office:body><office:spreadsheet><table:table table:name=\"Данные\"><table:table-row>");
            boolean quoted=false,atStart=true,rowStarted=true;int cells=0,ch;StringBuilder field=new StringBuilder();
            while((ch=reader.read())!=-1){
                char c=(char)ch;
                if(c=='"' && (atStart||quoted)){
                    if(quoted){int next=reader.read();if(next=='"')field.append('"');else{quoted=false;if(next!=-1)reader.unread(next);}}
                    else quoted=true;
                    atStart=false;continue;
                }
                if(!quoted && (c==separator||c=='\n'||c=='\r')){
                    if(++cells>MAX_CELLS)throw new IOException("CSV содержит больше миллиона ячеек");
                    cell(writer,field.toString());field.setLength(0);atStart=true;
                    if(c!=separator){writer.write("</table:table-row>");rowStarted=false;if(c=='\r'){int next=reader.read();if(next!='\n'&&next!=-1)reader.unread(next);}int next=reader.read();if(next!=-1){reader.unread(next);writer.write("<table:table-row>");rowStarted=true;}}
                }else{field.append(c);atStart=false;if(field.length()>MAX_FIELD)throw new IOException("Слишком длинное поле CSV");}
            }
            if(quoted)throw new IOException("Некорректный CSV: не закрыта кавычка");
            if(rowStarted){cell(writer,field.toString());writer.write("</table:table-row>");}
            writer.write("</table:table></office:spreadsheet></office:body></office:document>");
        } catch(IOException ex){out.delete();throw ex;}
        return out;
    }
    private static char guess(String sample){
        int[] counts=new int[3];char[] candidates={',',';','\t'};boolean quoted=false;
        for(int i=0;i<sample.length();i++){char c=sample.charAt(i);if(c=='"')quoted=!quoted;if(!quoted){if(c=='\r'||c=='\n')break;for(int j=0;j<3;j++)if(c==candidates[j])counts[j]++;}}
        int best=0;for(int i=1;i<3;i++)if(counts[i]>counts[best])best=i;return candidates[best];
    }
    private static void cell(Writer writer,String value)throws IOException{
        if(value.matches("-?(0|[1-9][0-9]{0,14})(\\.[0-9]+)?"))writer.write("<table:table-cell office:value-type=\"float\" office:value=\""+value+"\">");
        else writer.write("<table:table-cell office:value-type=\"string\">");
        writer.write("<text:p>");
        for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case '&':writer.write("&amp;");break;case '<':writer.write("&lt;");break;case '>':writer.write("&gt;");break;case '\n':writer.write("<text:line-break/>");break;default:if(c=='\t'||c>=32)writer.write(c);}}
        writer.write("</text:p></table:table-cell>");
    }
}
