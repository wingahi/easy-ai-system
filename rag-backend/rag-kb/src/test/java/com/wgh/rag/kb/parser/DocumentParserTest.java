package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DocumentParser 行为测试（重构前补，重构后保持绿）
 * 覆盖：各扩展名解析、未知格式报错
 */
@DisplayName("文档解析器行为测试")
class DocumentParserTest {

    @TempDir
    Path tempDir;

    private DocumentParserDispatcher parser;

    @BeforeEach
    void setUp() {
        parser = new DocumentParserDispatcher(List.of(
                new PdfParser(), new TxtParser(), new MdParser(), new CsvParser(),
                new XlsxParser(), new XlsParser(), new DocxParser(), new DocParser()));
    }

    /** TXT 解析按空行分段，去除空白并过滤空段。 */
    @Test
    @DisplayName("TXT - 空行分段、去空白、空段过滤")
    void parseTxt() throws Exception {
        File f = tempDir.resolve("a.txt").toFile();
        Files.writeString(f.toPath(), "第一段内容。\n\n  \n第二段内容。\n\n\n第三段内容。");

        List<String> result = parser.parse(f, "txt");

        assertThat(result).containsExactly("第一段内容。", "第二段内容。", "第三段内容。");
    }

    /** MD 解析沿用与 TXT 相同的空行分段逻辑，标题行保留原样。 */
    @Test
    @DisplayName("MD - 与 TXT 相同的分段逻辑")
    void parseMd() throws Exception {
        File f = tempDir.resolve("a.md").toFile();
        Files.writeString(f.toPath(), "# 标题\n\n正文段落。\n\n## 小节");

        List<String> result = parser.parse(f, "md");

        assertThat(result).containsExactly("# 标题", "正文段落。", "## 小节");
    }

    /** CSV 解析每行生成一个条目并过滤空行。 */
    @Test
    @DisplayName("CSV - 每行一个条目，空行过滤")
    void parseCsv() throws Exception {
        File f = tempDir.resolve("a.csv").toFile();
        Files.writeString(f.toPath(), "name,age\n张三,18\n\n李四,20\n");

        List<String> result = parser.parse(f, "csv");

        assertThat(result).containsExactly("name,age", "张三,18", "李四,20");
    }

    /** XLSX 逐行输出为「cell1 | cell2」格式，数值以整数形式呈现。 */
    @Test
    @DisplayName("XLSX - 逐行 cell1 | cell2 格式，数值整数化")
    void parseXlsx() throws Exception {
        File f = tempDir.resolve("a.xlsx").toFile();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("s1");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("名称");
            r0.createCell(1).setCellValue(18);
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("价格");
            r1.createCell(1).setCellValue(9.5);
            try (FileOutputStream out = new FileOutputStream(f)) {
                wb.write(out);
            }
        }

        List<String> result = parser.parse(f, "xlsx");

        assertThat(result).containsExactly("名称 | 18", "价格 | 9.5");
    }

    /** XLS 逐行输出为「cell1 | cell2」格式。 */
    @Test
    @DisplayName("XLS - 逐行 cell1 | cell2 格式")
    void parseXls() throws Exception {
        File f = tempDir.resolve("a.xls").toFile();
        try (Workbook wb = new HSSFWorkbook()) {
            Sheet sheet = wb.createSheet("s1");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("城市");
            r0.createCell(1).setCellValue("北京");
            try (FileOutputStream out = new FileOutputStream(f)) {
                wb.write(out);
            }
        }

        List<String> result = parser.parse(f, "xls");

        assertThat(result).containsExactly("城市 | 北京");
    }

    /** DOCX 解析输出段落文本，表格单元格以「 | 」连接。 */
    @Test
    @DisplayName("DOCX - 段落 + 表格单元格以 ' | ' 连接")
    void parseDocx() throws Exception {
        File f = tempDir.resolve("a.docx").toFile();
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("第一段文字");
            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("第二段文字");
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("左格");
            table.getRow(0).getCell(1).setText("右格");
            try (FileOutputStream out = new FileOutputStream(f)) {
                doc.write(out);
            }
        }

        List<String> result = parser.parse(f, "docx");

        assertThat(result).containsExactly("第一段文字", "第二段文字", "左格 | 右格");
    }

    /** PDF 解析能提取出页面文本并按空行分段。 */
    @Test
    @DisplayName("PDF - 提取文本并按空行分段")
    void parsePdf() throws Exception {
        File f = tempDir.resolve("a.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Hello PDF World");
                cs.endText();
            }
            doc.save(f);
        }

        List<String> result = parser.parse(f, "pdf");

        assertThat(result).isNotEmpty();
        assertThat(String.join("\n", result)).contains("Hello").contains("World");
    }

    /** 未知扩展名解析时抛 BusinessException，消息保留传入的原始大小写。 */
    @Test
    @DisplayName("未知格式 - 抛出 BusinessException，消息保留原始大小写")
    void parseUnknown() {
        File f = tempDir.resolve("a.html").toFile();

        assertThatThrownBy(() -> parser.parse(f, "HTML"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件格式: HTML");
    }
}
