package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PPTX 解析器（Apache POI XSLF）：按幻灯片顺序抽取文本框、表格与备注。
 */
@Slf4j
@Component
public class PptxParser implements DocumentParser {

    @Override
    public boolean supports(String extension) {
        return "pptx".equals(extension);
    }

    @Override
    public List<String> parse(File file) {
        return parseDetailed(file).paragraphs();
    }

    @Override
    public ParsedDocument parseDetailed(File file) {
        List<String> paragraphs = new ArrayList<>();
        int slideCount = 0;
        try (InputStream is = new FileInputStream(file);
             XMLSlideShow ppt = new XMLSlideShow(is)) {
            List<XSLFSlide> slides = ppt.getSlides();
            slideCount = slides.size();
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                List<String> slideTexts = new ArrayList<>();
                for (XSLFShape shape : slide.getShapes()) {
                    collectShapeText(shape, slideTexts);
                }
                if (!slideTexts.isEmpty()) {
                    paragraphs.add("第" + (i + 1) + "页：" + String.join(" ", slideTexts));
                }
            }
        } catch (IOException e) {
            log.error("解析PPTX失败: {}", e.getMessage(), e);
            throw new BusinessException("PPTX解析失败: " + e.getMessage());
        }
        return new ParsedDocument(paragraphs, DocumentMetadataSupport.inferTitle(paragraphs),
                slideCount, null, "binary", DocumentMetadataSupport.countChars(paragraphs));
    }

    private void collectShapeText(XSLFShape shape, List<String> out) {
        if (shape instanceof XSLFTextShape textShape) {
            String text = textShape.getText();
            if (text != null && !text.trim().isEmpty()) {
                out.add(text.trim().replaceAll("\\s+", " "));
            }
        } else if (shape instanceof XSLFTable table) {
            for (XSLFTableRow row : table.getRows()) {
                StringBuilder sb = new StringBuilder();
                for (XSLFTableCell cell : row.getCells()) {
                    if (cell.getText() != null && !cell.getText().trim().isEmpty()) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(cell.getText().trim());
                    }
                }
                if (sb.length() > 0) out.add(sb.toString());
            }
        }
    }
}
