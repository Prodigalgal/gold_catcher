package com.goldcatcher.information;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;

/** Read source cells and cached formula results; never evaluate formulas or follow external links. */
final class SpreadsheetArticle {
    private static final String NS="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    record Sheet(String name,String relation) {}

    static String extract(byte[] bytes) throws Exception {
        var parts=parts(bytes);var sheets=new ArrayList<Sheet>();
        read(required(parts,"xl/workbook.xml"),r->{
            if(r.getLocalName().equals("sheet")&&NS.equals(r.getNamespaceURI())) {
                sheets.add(new Sheet(r.getAttributeValue(null,"name"),r.getAttributeValue(REL,"id")));
                if(sheets.size()>32)throw invalid("SHEET_LIMIT");
            }
        });
        var targets=new HashMap<String,String>();
        read(required(parts,"xl/_rels/workbook.xml.rels"),r->{
            if(r.getLocalName().equals("Relationship")) {
                String target=r.getAttributeValue(null,"Target"),id=r.getAttributeValue(null,"Id");
                if("External".equals(r.getAttributeValue(null,"TargetMode")))return;
                if(target!=null&&id!=null) {
                    String path=target.startsWith("/")?target.substring(1):URI.create("xl/").resolve(target).normalize().toString();
                    if(targets.putIfAbsent(id,path)!=null)throw invalid("DUPLICATE_RELATIONSHIP");
                }
            }
        });
        var strings=new ArrayList<String>();
        if(parts.containsKey("xl/sharedStrings.xml")) {
            var r=xml(parts.get("xl/sharedStrings.xml"));StringBuilder value=null;
            try {while(r.hasNext()) {
                int event=r.next();
                if(event==XMLStreamConstants.START_ELEMENT&&r.getLocalName().equals("si"))value=new StringBuilder();
                else if(event==XMLStreamConstants.START_ELEMENT&&r.getLocalName().equals("t")&&value!=null) {
                    value.append(r.getElementText());if(value.length()>100000)throw invalid("CELL_LIMIT");
                } else if(event==XMLStreamConstants.END_ELEMENT&&r.getLocalName().equals("si")) {
                    strings.add(value==null?"":value.toString());value=null;
                    if(strings.size()>50000)throw invalid("SHARED_STRING_LIMIT");
                }
            }}finally{r.close();}
        }
        var text=new StringBuilder("XLSX 官方原始表格；数字、日期序号及公式缓存按来源保留，未执行公式；单位以表头为准。\n");
        int cells=0;
        for(var sheet:sheets) {
            String target=targets.get(sheet.relation());
            if(target==null||!target.matches("xl/worksheets/[A-Za-z0-9_-]+\\.xml"))throw invalid("SHEET_TARGET");
            text.append("\n工作表：").append(sheet.name()).append('\n');
            var r=xml(required(parts,target));
            try {while(r.hasNext()) {
                int event=r.next();
                if(event==XMLStreamConstants.START_ELEMENT&&r.getLocalName().equals("c")&&NS.equals(r.getNamespaceURI())) {
                    if(++cells>100000)throw invalid("CELL_COUNT_LIMIT");
                    String reference=r.getAttributeValue(null,"r"),type=r.getAttributeValue(null,"t");
                    if(reference==null||!reference.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))throw invalid("CELL_REFERENCE");
                    var value=new StringBuilder();boolean formula=false;
                    while(r.hasNext()) {
                        int nested=r.next();
                        if(nested==XMLStreamConstants.START_ELEMENT&&r.getLocalName().equals("f"))formula=true;
                        else if(nested==XMLStreamConstants.START_ELEMENT&&Set.of("v","t").contains(r.getLocalName())) {
                            value.append(r.getElementText());if(value.length()>100000)throw invalid("CELL_LIMIT");
                        } else if(nested==XMLStreamConstants.END_ELEMENT&&r.getLocalName().equals("c"))break;
                    }
                    String raw=value.toString();
                    if("s".equals(type)) {
                        int index=Integer.parseInt(raw);
                        if(index<0||index>=strings.size())throw invalid("STRING_REFERENCE");
                        raw=strings.get(index);
                    } else if(type!=null&&!Set.of("n","str","inlineStr","b","e","d").contains(type))throw invalid("CELL_TYPE");
                    if(!raw.isBlank())text.append(reference).append('=').append(raw).append(formula?" [公式缓存]":"").append('\t');
                } else if(event==XMLStreamConstants.END_ELEMENT&&r.getLocalName().equals("row"))text.append('\n');
                if(text.length()>1000000)throw invalid("TEXT_LIMIT");
            }}finally{r.close();}
        }
        if(cells==0||sheets.isEmpty())throw invalid("EMPTY");
        return text.toString().strip();
    }

    private static Map<String,byte[]> parts(byte[] bytes) throws IOException {
        var parts=new HashMap<String,byte[]>();int total=0,count=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;while((entry=zip.getNextEntry())!=null) {
                if(++count>256)throw invalid("ENTRY_LIMIT");
                var out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
                while((read=zip.read(buffer))!=-1) {
                    total+=read;if(total>16000000||out.size()+read>8000000)throw invalid("EXPANSION_LIMIT");
                    out.write(buffer,0,read);
                }
                if(parts.putIfAbsent(entry.getName(),out.toByteArray())!=null)throw invalid("DUPLICATE_ENTRY");
            }
        }
        return parts;
    }
    @FunctionalInterface private interface ElementReader {void accept(XMLStreamReader reader) throws Exception;}
    private static void read(byte[] bytes,ElementReader reader) throws Exception {
        var r=xml(bytes);try{while(r.hasNext())if(r.next()==XMLStreamConstants.START_ELEMENT)reader.accept(r);}finally{r.close();}
    }
    private static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setXMLResolver((p,s,b,n)->{throw new XMLStreamException("EXTERNAL_XML_REJECTED");});
        return new javax.xml.stream.util.StreamReaderDelegate(factory.createXMLStreamReader(new ByteArrayInputStream(bytes))) {
            @Override public int next() throws XMLStreamException {
                int event=super.next();if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE)throw new XMLStreamException("EXTERNAL_XML_REJECTED");return event;
            }
        };
    }
    private static byte[] required(Map<String,byte[]> parts,String path) {
        var bytes=parts.get(path);if(bytes==null)throw invalid("PART_MISSING");return bytes;
    }
    private static IllegalStateException invalid(String reason){return new IllegalStateException("ARTICLE_XLSX_"+reason);}
}
