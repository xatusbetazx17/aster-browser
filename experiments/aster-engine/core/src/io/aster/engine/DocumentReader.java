package io.aster.engine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Local UTF-8 text/Markdown and DOCX body/table text, without network or macros. */
public final class DocumentReader {
    public static String read(String name,InputStream input) throws Exception {
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".docx")){
            byte[] archive=PageAssets.read(input,10*1024*1024,System.nanoTime()+10_000_000_000L);
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))){ZipEntry entry;int count=0;
                while((entry=zip.getNextEntry())!=null){
                    if(++count>2048)throw new IOException("Document contains too many entries.");
                    if(!entry.getName().equals("word/document.xml")){PageAssets.read(zip,2*1024*1024,System.nanoTime()+2_000_000_000L);continue;}
                    byte[] xml=PageAssets.read(zip,2*1024*1024,System.nanoTime()+5_000_000_000L);
                    // Accept ordinary UTF-8 Word XML only; refuse DTD/entity declarations before parsing.
                    String source=new String(xml,StandardCharsets.UTF_8);
                    if(source.indexOf('\0')>=0||source.contains("<!DOCTYPE")||source.contains("<!ENTITY"))throw new IOException("Document XML encoding or declarations are unsupported.");
                    SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);
                    factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                    StringBuilder text=new StringBuilder();
                    DefaultHandler handler=new DefaultHandler(){boolean inText;
                        public InputSource resolveEntity(String a,String b)throws SAXException{throw new SAXException("External entities refused");}
                        public void startElement(String uri,String local,String q,Attributes a){
                            if(local.equals("t"))inText=true;else if(local.equals("tab"))text.append('\t');else if(local.equals("br"))text.append('\n');}
                        public void characters(char[] c,int at,int n)throws SAXException{if(inText)text.append(c,at,n);if(text.length()>1_000_000)throw new SAXException("Document text too large");}
                        public void endElement(String uri,String local,String q){if(local.equals("t"))inText=false;if(local.equals("p")||local.equals("tr"))text.append('\n');if(local.equals("tc"))text.append('\t');}
                    };
                    factory.newSAXParser().parse(new ByteArrayInputStream(xml),handler);return text.toString().trim();
                }
            }
            throw new IOException("This file has no Word document body.");
        }
        if(!lower.endsWith(".txt")&&!lower.endsWith(".md"))throw new IOException("Choose a .txt, .md or .docx document. PDF reading is not included yet.");
        return new String(PageAssets.read(input,1_000_000,System.nanoTime()+10_000_000_000L),StandardCharsets.UTF_8);
    }
    private DocumentReader() { }
}
