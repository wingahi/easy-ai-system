package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentParserDispatcher 注册分发测试
 */
@DisplayName("文档解析分发器测试")
class DocumentParserDispatcherTest {

    /** 扩展名转小写后命中 supports 的解析器，仅委托该解析器处理。 */
    @Test
    @DisplayName("分发 - 命中 supports 的实现并委托解析（扩展名转小写）")
    void dispatchToSupportingParser() {
        DocumentParser pdf = mock(DocumentParser.class);
        DocumentParser txt = mock(DocumentParser.class);
        when(pdf.supports("pdf")).thenReturn(true);
        when(pdf.parse(any())).thenReturn(List.of("段落"));
        File f = new File("a.pdf");

        DocumentParserDispatcher dispatcher = new DocumentParserDispatcher(List.of(txt, pdf));
        List<String> result = dispatcher.parse(f, "PDF");

        assertThat(result).containsExactly("段落");
        verify(pdf).parse(f);
        verify(txt, never()).parse(any());
    }

    /** 无解析器支持该扩展名时抛 BusinessException，消息保留原始大小写。 */
    @Test
    @DisplayName("未知格式 - 抛出 BusinessException，消息保留原始大小写")
    void unknownExtensionThrows() {
        DocumentParser pdf = mock(DocumentParser.class);
        when(pdf.supports(any())).thenReturn(false);

        DocumentParserDispatcher dispatcher = new DocumentParserDispatcher(List.of(pdf));

        assertThatThrownBy(() -> dispatcher.parse(new File("a.HTML"), "HTML"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件格式: HTML");
    }

    /** 内置的八个解析器覆盖 pdf、txt、md、csv、xlsx、xls、docx、doc 全部格式。 */
    @Test
    @DisplayName("注册 - 内置解析器覆盖 pdf/txt/md/csv/xlsx/xls/docx/doc 八种格式")
    void builtinParsersSupportAllExtensions() {
        List<DocumentParser> parsers = List.of(
                new PdfParser(), new TxtParser(), new MdParser(), new CsvParser(),
                new XlsxParser(), new XlsParser(), new DocxParser(), new DocParser());
        DocumentParserDispatcher dispatcher = new DocumentParserDispatcher(parsers);

        for (String ext : List.of("pdf", "txt", "md", "csv", "xlsx", "xls", "docx", "doc")) {
            assertThat(parsers.stream().anyMatch(p -> p.supports(ext)))
                    .as("应有解析器支持 %s", ext).isTrue();
        }
    }
}
