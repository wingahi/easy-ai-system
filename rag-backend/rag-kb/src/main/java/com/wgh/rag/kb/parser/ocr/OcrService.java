package com.wgh.rag.kb.parser.ocr;

import java.awt.image.BufferedImage;

/**
 * OCR 能力抽象：扫描件/图片型 PDF 的文字识别入口。
 * <p>
 * 实现必须满足“缺引擎可降级”的约束：{@link #isAvailable()} 仅做能力探测，不得抛异常；
 * {@link #recognize(BufferedImage)} 在引擎不可用或识别失败时返回空串，由调用方回退到文本抽取。
 */
public interface OcrService {

    /** 引擎是否可用（探测结果应缓存，避免每次解析都启动外部进程）。 */
    boolean isAvailable();

    /**
     * 识别一张页面图片。
     *
     * @return 识别出的纯文本；引擎不可用/失败时返回空串，不得返回 null
     */
    String recognize(BufferedImage image);

    /** 引擎名称，用于日志与诊断。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
