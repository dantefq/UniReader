package app.quietread;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Print-layout changes happen only in a temporary ODS copy, never in the user's workbook. */
final class SpreadsheetPdf {
    private static final String STYLE="urn:oasis:names:tc:opendocument:xmlns:style:1.0",TABLE="urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    static void exportFit(ReaderEngine source,File directory,File target)throws Exception {
        File ods=new File(directory,"source.ods"),fit=new File(directory,"fit.ods");source.export(ods,"ods","");
        try(ZipFile zip=new ZipFile(ods);ZipOutputStream out=new ZipOutputStream(new FileOutputStream(fit))){java.util.Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){
            ZipEntry entry=entries.nextElement();byte[] data;try(InputStream in=zip.getInputStream(entry);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] chunk=new byte[32768];int n;while((n=in.read(chunk))!=-1)bytes.write(chunk,0,n);data=bytes.toByteArray();}
            if(entry.getName().equals("styles.xml")||entry.getName().equals("content.xml"))data=layout(data);
            out.putNextEntry(new ZipEntry(entry.getName()));out.write(data);out.closeEntry();
        }}
        org.libreoffice.kit.Document temporary=ReaderEngine.office.documentLoad(fit.toURI().toASCIIString());
        if(temporary==null)throw new IOException("Не удалось подготовить печатную разметку");
        try{temporary.initializeForRendering();temporary.saveAs(target.toURI().toASCIIString(),"pdf","");String error=ReaderEngine.office.getError();if(!target.isFile()||target.length()==0||(error!=null&&!error.isBlank()))throw new IOException("Не удалось экспортировать таблицу: "+error);}
        finally{temporary.destroy();ods.delete();fit.delete();source.document.setPart(source.part);}
    }
    private static byte[] layout(byte[] input)throws Exception {
        if(new String(input,StandardCharsets.UTF_8).contains("<!DOCTYPE"))throw new IOException("Недопустимый DTD в печатной копии");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setExpandEntityReferences(false);
        DocumentBuilder builder=factory.newDocumentBuilder();builder.setEntityResolver((publicId,systemId)->new org.xml.sax.InputSource(new StringReader("")));
        Document xml=builder.parse(new ByteArrayInputStream(input));
        NodeList layouts=xml.getElementsByTagNameNS(STYLE,"page-layout-properties");
        for(int i=0;i<layouts.getLength();i++){Element e=(Element)layouts.item(i);for(String n:new String[]{"scale-to","scale-to-pages","scale-to-X","scale-to-Y"})e.removeAttributeNS(STYLE,n);e.setAttributeNS(STYLE,"style:scale-to-X","1");}
        // Explicit print ranges can silently omit columns/rows. Fit mode exports the whole used area.
        NodeList tables=xml.getElementsByTagNameNS(TABLE,"table");for(int i=0;i<tables.getLength();i++){Element e=(Element)tables.item(i);e.removeAttributeNS(TABLE,"print-ranges");}
        ByteArrayOutputStream out=new ByteArrayOutputStream();Transformer transformer=TransformerFactory.newInstance().newTransformer();transformer.setOutputProperty(OutputKeys.ENCODING,StandardCharsets.UTF_8.name());transformer.transform(new DOMSource(xml),new StreamResult(out));return out.toByteArray();
    }
}
