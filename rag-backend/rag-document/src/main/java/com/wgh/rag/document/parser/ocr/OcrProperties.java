package com.wgh.rag.document.parser.ocr;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OCR 解析配置。
 * <p>
 * 默认值即可工作；如需覆盖，通过环境变量或配置项注入：
 * <ul>
 *   <li>{@code WGH_OCR_ENABLED} → {@code wgh.ocr.enabled}</li>
 *   <li>{@code WGH_OCR_LANG} → {@code wgh.ocr.lang}</li>
 *   <li>{@code WGH_OCR_MIN_TEXT_PER_PAGE} → {@code wgh.ocr.min-text-per-page}</li>
 * </ul>
 * 不修改 application.yml，避免与其他任务争抢配置文件。
 */
@Data
@Component
public class OcrProperties {

    /** OCR 总开关；关闭后扫描件直接回退文本抽取。 */
    @Value("${wgh.ocr.enabled:true}")
    private boolean enabled = true;

    /** Tesseract 语言包，例如 chi_sim+eng。 */
    @Value("${wgh.ocr.lang:chi_sim+eng}")
    private String lang = "chi_sim+eng";

    /** 单页文字数低于该值时触发 OCR。 */
    @Value("${wgh.ocr.min-text-per-page:20}")
    private int minTextPerPage = 20;

    /** PDF 渲染 DPI（越高越清晰，耗时与内存也越高）。 */
    @Value("${wgh.ocr.dpi:200}")
    private int dpi = 200;

    /** 单页 OCR 超时（秒），超时后回退文本抽取。 */
    @Value("${wgh.ocr.timeout-seconds:60}")
    private int timeoutSeconds = 60;

    /** 单文档最多 OCR 页数，避免超大扫描件长时间占用线程。 */
    @Value("${wgh.ocr.max-pages:50}")
    private int maxPages = 50;

    /** Tesseract 可执行命令（默认从 PATH 查找）。 */
    @Value("${wgh.ocr.command:tesseract}")
    private String command = "tesseract";
}
