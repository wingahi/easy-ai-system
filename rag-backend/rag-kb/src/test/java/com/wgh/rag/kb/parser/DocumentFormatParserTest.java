package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 新增文档格式（pptx / html / json / eml）与统一元数据、异常保护测试。
 */
@DisplayName("新增文档格式解析测试")
class DocumentFormatParserTest {

    @TempDir
    Path tempDir;

    private DocumentParserDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new DocumentParserDispatcher(List.of(
                new PdfParser(), new TxtParser(), new MdParser(), new CsvParser(),
                new XlsxParser(), new XlsParser(), new DocxParser(), new DocParser(),
                new PptxParser(), new HtmlParser(), new JsonParser(), new EmlParser()));
    }

    /** PPTX 按页抽取文本框，元数据包含幻灯片页数。 */
    @Test
    @DisplayName("PPTX - 按页抽取文本并返回页数")
    void parsePptx() throws Exception {
        File f = tempDir.resolve("demo.pptx").toFile();
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("采购审批流程说明");
            ppt.createSlide().createTextBox().setText("第二页内容");
            try (OutputStream out = Files.newOutputStream(f.toPath())) {
                ppt.write(out);
            }
        }

        ParsedDocument doc = dispatcher.parseDocument(f, "pptx");

        assertThat(doc.paragraphs()).anyMatch(p -> p.contains("采购审批流程说明"));
        assertThat(doc.pageCount()).isEqualTo(2);
        assertThat(doc.metadata()).containsEntry("pageCount", 2).containsKey("charCount");
    }

    /** HTML 提取 title 与块级正文，忽略 script/style。 */
    @Test
    @DisplayName("HTML - 提取 title 与正文，忽略脚本样式")
    void parseHtml() throws Exception {
        File f = tempDir.resolve("demo.html").toFile();
        Files.writeString(f.toPath(), """
                <html><head><title>报销制度</title><style>.x{}</style></head>
                <body><script>alert(1)</script>
                <h1>差旅报销</h1><p>住宿标准每晚上限 500 元。</p><li>需提供发票</li>
                </body></html>
                """, StandardCharsets.UTF_8);

        ParsedDocument doc = dispatcher.parseDocument(f, "html");

        assertThat(doc.title()).isEqualTo("报销制度");
        assertThat(doc.paragraphs()).contains("差旅报销", "住宿标准每晚上限 500 元。", "需提供发票");
        assertThat(doc.paragraphs()).noneMatch(p -> p.contains("alert"));
        assertThat(doc.encoding()).isNotBlank();
    }

    /** JSON 扁平化为「路径: 值」段落，标题优先取 title 字段。 */
    @Test
    @DisplayName("JSON - 扁平化嵌套对象并识别 title")
    void parseJson() throws Exception {
        File f = tempDir.resolve("demo.json").toFile();
        Files.writeString(f.toPath(), """
                {"title":"产品配置","name":"RAG","spec":{"topK":5,"hybrid":true},
                 "tags":["检索","问答"]}
                """, StandardCharsets.UTF_8);

        ParsedDocument doc = dispatcher.parseDocument(f, "json");

        assertThat(doc.title()).isEqualTo("产品配置");
        assertThat(doc.paragraphs()).anyMatch(p -> p.contains("spec.topK: 5"))
                .anyMatch(p -> p.contains("tags[0]: 检索"));
        assertThat(doc.encoding()).isEqualTo("UTF-8");
    }

    /** EML 抽取主题/发件人/正文，主题作为标题。 */
    @Test
    @DisplayName("EML - 抽取主题、发件人与正文")
    void parseEml() throws Exception {
        File f = tempDir.resolve("demo.eml").toFile();
        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("hr@example.com"));
        msg.setRecipients(MimeMessage.RecipientType.TO, "staff@example.com");
        msg.setSubject("年假申请流程", "UTF-8");
        msg.setText("请提前三个工作日在系统提交申请。", "UTF-8");
        try (OutputStream out = Files.newOutputStream(f.toPath())) {
            msg.writeTo(out);
        }

        ParsedDocument doc = dispatcher.parseDocument(f, "eml");

        assertThat(doc.title()).isEqualTo("年假申请流程");
        assertThat(doc.paragraphs()).anyMatch(p -> p.contains("hr@example.com"))
                .anyMatch(p -> p.contains("请提前三个工作日"));
    }

    /** 文本编码识别：GBK 文件不出现 UTF-8 乱码。 */
    @Test
    @DisplayName("编码识别 - GBK 文本按 GBK 读取")
    void parseGbkText() throws Exception {
        File f = tempDir.resolve("gbk.txt").toFile();
        Files.write(f.toPath(), "中文编码测试".getBytes(Charset.forName("GBK")));

        ParsedDocument doc = dispatcher.parseDocument(f, "txt");

        assertThat(doc.encoding()).isEqualTo("GBK");
        assertThat(doc.paragraphs()).contains("中文编码测试");
    }

    /** 损坏 PDF 返回友好业务错误，不抛出底层 IO 异常。 */
    @Test
    @DisplayName("损坏文件 - 返回友好解析失败提示")
    void corruptFileFriendlyError() throws Exception {
        File f = tempDir.resolve("broken.pdf").toFile();
        Files.writeString(f.toPath(), "not a real pdf");

        assertThatThrownBy(() -> dispatcher.parse(f, "pdf"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件可能已损坏");
    }

    /** 超过解析大小上限时拒绝并给出上限提示。 */
    @Test
    @DisplayName("大小上限 - 超限文件拒绝解析")
    void oversizedFileRejected() throws Exception {
        File f = tempDir.resolve("big.txt").toFile();
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(2L * 1024 * 1024);
        }
        dispatcher.setMaxSingleFileMb(1);

        assertThatThrownBy(() -> dispatcher.parse(f, "txt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过解析上限 1 MB");
    }

    /** 空文件拒绝解析。 */
    @Test
    @DisplayName("空文件 - 拒绝解析")
    void emptyFileRejected() throws Exception {
        File f = tempDir.resolve("empty.txt").toFile();
        Files.writeString(f.toPath(), "");

        assertThatThrownBy(() -> dispatcher.parse(f, "txt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件为空");
    }
}
