package com.goldcatcher.analysis;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class GldArchiveTest {
    private static final Instant AT=Instant.parse("2026-10-10T12:00:00Z");
    @Test void exactHeadersAndWorkbookRelationshipSelectHoldingsAndKeepSourceDecimals() throws Exception {
        var rows=GldArchive.parse(fixture("Tonnes of Gold","08-Oct-2026","1055.41000000000008",false),AT,"hash","url");
        assertThat(rows).hasSize(2);assertThat(rows.getLast().period()).isEqualTo("2026-10-08");
        assertThat(rows.getLast().values()).containsEntry("tonnes","1055.41000000000008").containsEntry("ounces","33932506.40999999642372");
        assertThat(rows.getLast().publishedAt()).isNull();assertThat(rows.getLast().receivedAt()).isEqualTo(AT);
        assertThat(rows.getLast().unit()).isEqualTo("FIELD_SPECIFIC");
    }
    @Test void wrongHeaderFutureDatesNegativeNumbersAndCachedFormulasAreRejected() throws Exception {
        assertThatThrownBy(()->GldArchive.parse(fixture("Tonnes of Silver","08-Oct-2026","100",false),AT,"hash","url")).hasMessage("GLD_ARCHIVE_SCHEMA_UNKNOWN");
        assertThatThrownBy(()->GldArchive.parse(fixture("Tonnes of Gold","12-Oct-2026","100",false),AT,"hash","url")).hasMessage("GLD_ASOF_FUTURE");
        assertThatThrownBy(()->GldArchive.parse(fixture("Tonnes of Gold","08-Oct-2026","-100",false),AT,"hash","url")).hasMessage("GLD_NUMBER_INVALID");
        assertThatThrownBy(()->GldArchive.parse(fixture("Tonnes of Gold","08-Oct-2026","100",true),AT,"hash","url")).hasMessage("GLD_ARCHIVE_SCHEMA_UNKNOWN");
        assertThatThrownBy(()->GldArchive.parse("<html>access denied</html>".getBytes(),AT,"hash","url")).hasMessage("GLD_NOT_XLSX");
    }
    @Test void unboundedZipDataAndExternalWorksheetRelationshipsAreRejected() throws Exception {
        var parts=base();parts.put("xl/worksheets/sheet2.xml","x".repeat(8000001));
        byte[] oversized=zip(parts);assertThatThrownBy(()->GldArchive.parse(oversized,AT,"hash","url")).hasMessage("GLD_ARCHIVE_TOO_LARGE");
        parts=base();parts.put("xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='rId5' Target='https://example.com/data.xml' TargetMode='External'/></Relationships>");
        byte[] external=zip(parts);assertThatThrownBy(()->GldArchive.parse(external,AT,"hash","url")).hasMessage("GLD_ARCHIVE_SCHEMA_UNKNOWN");
    }
    private byte[] fixture(String header,String date,String tonnes,boolean formula) throws IOException {
        var parts=base();parts.put("xl/worksheets/sheet2.xml","<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData>"+
            "<row r='1'>"+cell("A1","Date",true)+cell("C1","Ounces of Gold per Share",true)+cell("I1","Total Ounces of Gold in the Trust",true)+cell("J1",header,true)+cell("K1","Total Net Asset Value in the Trust",true)+"</row>"+
            row(2,"06-Oct-2026","1059.97",false)+row(3,"07-Oct-2026","1061.11",false)+row(4,date,tonnes,formula)+"</sheetData></worksheet>");return zip(parts);
    }
    private String row(int n,String date,String tonnes,boolean formula){return "<row r='"+n+"'>"+cell("A"+n,date,true)+cell("C"+n,"0.09166979",false)+cell("I"+n,"33932506.40999999642372",false)+
        (formula?"<c r='J"+n+"'><f>I"+n+"/32000</f><v>"+tonnes+"</v></c>":cell("J"+n,tonnes,false))+cell("K"+n,"139895758789.8699951171875",false)+"</row>";}
    private String cell(String ref,String value,boolean text){return "<c r='"+ref+"'"+(text?" t='inlineStr'><is><t>"+value+"</t></is>":"><v>"+value+"</v>")+"</c>";}
    private Map<String,String> base(){var parts=new LinkedHashMap<String,String>();parts.put("xl/workbook.xml","<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Disclaimer' sheetId='1' r:id='rId4'/><sheet name='US GLD Historical Archive' sheetId='2' r:id='rId5'/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='rId5' Target='worksheets/sheet2.xml'/></Relationships>");parts.put("xl/sharedStrings.xml","<sst xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'/>");return parts;}
    private byte[] zip(Map<String,String> parts) throws IOException {var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var part:parts.entrySet()){zip.putNextEntry(new ZipEntry(part.getKey()));zip.write(part.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}}return bytes.toByteArray();}
}
