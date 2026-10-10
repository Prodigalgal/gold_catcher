package com.goldcatcher.information;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class SpreadsheetArticleTest {
    @Test void preservesCellsSharedRichTextAndFormulaCacheWithoutEvaluation() throws Exception {
        var parts=fixture();parts.put("xl/sharedStrings.xml","<sst xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><si><r><t>持仓</t></r><r><t>（公吨）</t></r></si></sst>");
        parts.put("xl/worksheets/sheet1.xml","<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row r='1'><c r='A1' t='s'><v>0</v></c><c r='C1'><v>0</v></c></row><row r='2'><c r='A2' t='inlineStr'><is><t>2026-10-09</t></is></c><c r='B2'><v>100.123456789012345</v></c><c r='C2'><f>B2*2</f><v>200.24691357802469</v></c></row></sheetData></worksheet>");
        var result=OfficialArticle.extract("BOJ_NEWS",zip(parts),"https://www.boj.or.jp/data.xlsx");
        assertThat(result.format()).isEqualTo("XLSX_SOURCE_CELLS");assertThat(result.releaseAt()).isNull();
        assertThat(result.text()).contains("A1=持仓（公吨）","C1=0","A2=2026-10-09","B2=100.123456789012345","C2=200.24691357802469 [公式缓存]");
    }
    @Test void externalSheetAndXmlEntitiesAreRejected() {
        var parts=fixture();parts.put("xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='r1' TargetMode='External' Target='https://example.com/secret.xml'/></Relationships>");
        var external=parts;assertThatThrownBy(()->SpreadsheetArticle.extract(zip(external))).hasMessage("ARTICLE_XLSX_SHEET_TARGET");
        parts=fixture();parts.put("xl/workbook.xml","<!DOCTYPE workbook [<!ENTITY secret SYSTEM 'file:///never-read'>]><workbook>&secret;</workbook>");
        var hostile=parts;assertThatThrownBy(()->SpreadsheetArticle.extract(zip(hostile))).isInstanceOf(javax.xml.stream.XMLStreamException.class);
    }
    private Map<String,String> fixture(){
        var parts=new LinkedHashMap<String,String>();
        parts.put("xl/workbook.xml","<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='统计资料' r:id='r1'/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='r1' Target='worksheets/sheet1.xml'/></Relationships>");return parts;
    }
    private byte[] zip(Map<String,String> parts) throws IOException {
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var p:parts.entrySet()){zip.putNextEntry(new ZipEntry(p.getKey()));zip.write(p.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return out.toByteArray();
    }
}
