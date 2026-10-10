package com.goldcatcher.analysis;

import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;

/** Strict, bounded XLSX reader for the issuer's GLD holdings archive; numeric source strings stay decimal. */
final class GldArchive {
    private static final String NS="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final Map<String,String> HEADERS=Map.of("A","Date","C","Ounces of Gold per Share","I","Total Ounces of Gold in the Trust","J","Tonnes of Gold","K","Total Net Asset Value in the Trust");
    private static final Map<String,String> FIELDS=Map.of("C","ouncesPerShare","I","ounces","J","tonnes","K","netAssetValueUsd");
    static List<SupplementalSources.Observation> parse(byte[] bytes,Instant at,String hash,String url) throws Exception {
        var parts=parts(bytes);String relationship=null;
        var workbook=xml(part(parts,"xl/workbook.xml"));
        try{while(workbook.hasNext()){int event=workbook.next();if(event==XMLStreamConstants.START_ELEMENT&&workbook.getLocalName().equals("sheet")&&NS.equals(workbook.getNamespaceURI())&&"US GLD Historical Archive".equals(workbook.getAttributeValue(null,"name"))){
            if(relationship!=null)throw invalid();relationship=workbook.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");
        }}}finally{workbook.close();}
        if(relationship==null)throw invalid();String target=null;var rels=xml(part(parts,"xl/_rels/workbook.xml.rels"));
        try{while(rels.hasNext()){int event=rels.next();if(event==XMLStreamConstants.START_ELEMENT&&rels.getLocalName().equals("Relationship")&&relationship.equals(rels.getAttributeValue(null,"Id"))){
            if(target!=null||"External".equals(rels.getAttributeValue(null,"TargetMode")))throw invalid();
            target=URI.create("xl/").resolve(Objects.requireNonNull(rels.getAttributeValue(null,"Target"))).normalize().toString();
        }}}finally{rels.close();}
        if(target==null||!target.matches("xl/worksheets/[A-Za-z0-9_-]+\\.xml"))throw invalid();
        var strings=new ArrayList<String>();var shared=xml(part(parts,"xl/sharedStrings.xml"));
        try{StringBuilder text=null;while(shared.hasNext()){int event=shared.next();
            if(event==XMLStreamConstants.START_ELEMENT&&shared.getLocalName().equals("si"))text=new StringBuilder();
            if(event==XMLStreamConstants.START_ELEMENT&&shared.getLocalName().equals("t")&&text!=null){text.append(shared.getElementText());if(text.length()>100000)throw invalid();}
            if(event==XMLStreamConstants.END_ELEMENT&&shared.getLocalName().equals("si")){strings.add(text.toString());text=null;if(strings.size()>50000)throw invalid();}
        }}finally{shared.close();}
        var latest=new TreeMap<LocalDate,Map<String,String>>();var sheet=xml(part(parts,target));boolean header=false;Map<String,String> row=null;
        LocalDate today=at.atZone(ZoneId.of("America/New_York")).toLocalDate();
        try{if(sheet.nextTag()!=XMLStreamConstants.START_ELEMENT||!sheet.getLocalName().equals("worksheet")||!NS.equals(sheet.getNamespaceURI()))throw invalid();
          while(sheet.hasNext()){int event=sheet.next();
            if(event==XMLStreamConstants.START_ELEMENT&&sheet.getLocalName().equals("row")){row=new LinkedHashMap<>();}
            else if(event==XMLStreamConstants.START_ELEMENT&&sheet.getLocalName().equals("c")&&row!=null){
                String reference=sheet.getAttributeValue(null,"r"),type=sheet.getAttributeValue(null,"t");
                if(reference==null||!reference.matches("[A-Z]{1,3}[1-9][0-9]{0,5}"))throw invalid();
                String column=reference.replaceAll("[0-9]","");String value="";boolean formula=false;
                while(sheet.hasNext()){int nested=sheet.next();if(nested==XMLStreamConstants.START_ELEMENT){
                    if(sheet.getLocalName().equals("f"))formula=true;
                    if(Set.of("v","t").contains(sheet.getLocalName()))value+=sheet.getElementText();
                }else if(nested==XMLStreamConstants.END_ELEMENT&&sheet.getLocalName().equals("c"))break;}
                if((formula&&HEADERS.containsKey(column))||value.length()>100000)throw invalid();
                if("s".equals(type)){int index=Integer.parseInt(value);if(index<0||index>=strings.size())throw invalid();value=strings.get(index);}
                else if(type!=null&&!Set.of("inlineStr","str","n").contains(type))throw invalid();
                if(row.putIfAbsent(column,value)!=null)throw invalid();
            }else if(event==XMLStreamConstants.END_ELEMENT&&sheet.getLocalName().equals("row")&&row!=null){
                if(row.values().stream().allMatch(String::isBlank)){row=null;continue;}
                if(!header){for(var h:HEADERS.entrySet())if(!h.getValue().equals(row.get(h.getKey())))throw invalid();header=true;}
                else{
                    var date=LocalDate.parse(Objects.requireNonNull(row.get("A")),DateTimeFormatter.ofPattern("dd-MMM-uuuu",Locale.US));
                    if(date.isAfter(today))throw new IllegalStateException("GLD_ASOF_FUTURE");
                    // Explicit non-trading rows do not contain a new holdings observation.
                    if(row.values().stream().anyMatch(v->v.equals("US Holiday")||v.equals("HOLIDAY"))){row=null;continue;}
                    var values=new LinkedHashMap<String,String>();
                    for(var field:FIELDS.entrySet()){String value=row.get(field.getKey());if(value==null||!value.matches("[0-9]{1,18}(?:\\.[0-9]{1,20})?")||new BigDecimal(value).signum()<=0)throw new IllegalStateException("GLD_NUMBER_INVALID");values.put(field.getValue(),new BigDecimal(value).stripTrailingZeros().toPlainString());}
                    var old=latest.putIfAbsent(date,values);if(old!=null&&!old.equals(values))throw new IllegalStateException("GLD_DATE_CONFLICT");
                    while(latest.size()>2)latest.pollFirstEntry();
                }row=null;
            }
        }}finally{sheet.close();}
        if(!header||latest.isEmpty())throw invalid();
        return latest.entrySet().stream().map(e->new SupplementalSources.Observation("GLD:"+e.getKey(),"GLD_HOLDINGS","GLD 发行方黄金持仓",e.getKey().toString(),"DAILY","FIELD_SPECIFIC",Map.copyOf(e.getValue()),null,at,url,hash,
            "发行方历史报表最近两个可用统计日；ounces为金衡盎司，tonnes为公吨，ouncesPerShare为每份额金衡盎司，netAssetValueUsd为USD。保留XLSX原始十进制数值，源文件可能包含浮点尾数；不是盘中申赎流，无精确首发时刻。" )).toList();
    }
    private static Map<String,byte[]> parts(byte[] bytes) throws IOException {
        if(bytes.length<4||bytes[0]!='P'||bytes[1]!='K')throw new IllegalStateException("GLD_NOT_XLSX");
        var result=new HashMap<String,byte[]>();int total=0,count=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
            if(++count>64)throw invalid();var out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
            while((read=zip.read(buffer))!=-1){total+=read;if(total>16000000||out.size()+read>8000000)throw new IllegalStateException("GLD_ARCHIVE_TOO_LARGE");out.write(buffer,0,read);}
            if(result.putIfAbsent(entry.getName(),out.toByteArray())!=null)throw invalid();
        }}return result;
    }
    private static byte[] part(Map<String,byte[]> parts,String name){if(!parts.containsKey(name))throw invalid();return parts.get(name);}
    private static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("GLD_EXTERNAL_XML_REJECTED");});
        return new javax.xml.stream.util.StreamReaderDelegate(factory.createXMLStreamReader(new ByteArrayInputStream(bytes))){
            @Override public int next() throws XMLStreamException {int event=super.next();if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE)throw new XMLStreamException("GLD_EXTERNAL_XML_REJECTED");return event;}
        };
    }
    private static IllegalStateException invalid(){return new IllegalStateException("GLD_ARCHIVE_SCHEMA_UNKNOWN");}
}
