package com.wuye.agent.attachment;

/**
 * 主 Agent 路由附件时给下游的上下文：不传原始字节，只传附件引用和解析文本。
 */
public record ChatAttachmentContext(
        String attachmentNo,
        String fileName,
        String parseKind,
        String parseStatus,
        String documentText,
        int pageCount,
        DomainDecision domain
) {
    public boolean needsVision() {
        return "COMPLEX_MULTIMODAL".equals(parseKind);
    }

    public String promptText(int maxChars) {
        if (documentText == null) {
            return "";
        }
        return documentText.length() <= maxChars
                ? documentText
                : documentText.substring(0, maxChars);
    }
}
