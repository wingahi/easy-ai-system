package com.wgh.rag.document.parser.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 默认 OCR 实现：调用本机 Tesseract 命令行。
 * <p>
 * 选择 CLI 而非引入额外 Java OCR 依赖的原因：项目已长期使用 Tesseract 生态，
 * 命令行方式不会给打包体积增加体积，也便于在无引擎环境直接判定不可用并降级。
 * 所有异常均在内部消化，调用方只会得到空串，不会中断上传/解析主链路。
 */
@Slf4j
@Component
public class TessOcrService implements OcrService {

    private final OcrProperties properties;

    /** 探测结果缓存：null 表示尚未探测。 */
    private volatile Boolean available;

    public TessOcrService(OcrProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "tesseract-cli";
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (available == null) {
                available = probe();
            }
            return available;
        }
    }

    @Override
    public String recognize(BufferedImage image) {
        if (image == null || !properties.isEnabled() || !isAvailable()) {
            return "";
        }
        Path temp = null;
        try {
            temp = Files.createTempFile("wgh-ocr-", ".png");
            ImageIO.write(image, "png", temp.toFile());

            List<String> command = new ArrayList<>();
            command.add(properties.getCommand());
            command.add(temp.toAbsolutePath().toString());
            command.add("stdout");
            if (properties.getLang() != null && !properties.getLang().isBlank()) {
                command.add("-l");
                command.add(properties.getLang());
            }

            ProcessBuilder builder = new ProcessBuilder(command);
            // tesseract 的提示信息走 stderr，丢弃以免污染识别文本
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();

            byte[] output;
            try (InputStream in = process.getInputStream()) {
                output = in.readAllBytes();
            }
            boolean finished = process.waitFor(
                    Math.max(1, properties.getTimeoutSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("OCR 超时（{}s），已跳过该页并回退文本抽取", properties.getTimeoutSeconds());
                return "";
            }
            if (process.exitValue() != 0) {
                log.warn("OCR 进程退出码 {}，已跳过该页并回退文本抽取", process.exitValue());
                return "";
            }
            return new String(output, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("OCR 调用被中断，已回退文本抽取: {}", e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("OCR 调用失败，已回退文本抽取: {}", e.getMessage());
            return "";
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    /** 探测 Tesseract 是否可执行；任何异常都视为不可用。 */
    private boolean probe() {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(properties.getCommand(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            boolean ok = finished && process.exitValue() == 0;
            if (ok) {
                log.info("OCR 引擎可用: {}", firstLine(output));
            } else {
                log.warn("OCR 引擎不可用，扫描页将回退文本抽取");
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("未检测到 OCR 引擎（{}），扫描页将回退文本抽取: {}",
                    properties.getCommand(), e.getMessage());
            return false;
        }
    }

    private String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int idx = text.indexOf('\n');
        String line = idx >= 0 ? text.substring(0, idx) : text;
        return line.trim();
    }
}
