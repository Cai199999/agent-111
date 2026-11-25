package com.wuye.agent.attachment;

import java.time.Instant;

/**
 * 主 Agent 聊天附件：元数据 + 解析结果。
 */
public record ChatAttachmentRecord(
        String attachmentNo,
        String sessionNo,
        String uploaderActorId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        String storagePath,
        String parseKind,
        String parseStatus,
        Integer pageCount,
        int textChars,
        String extractedText,
        Instant createdAt
) {
    public boolean parsedAsText() {
        return "SIMPLE_TEXT".equals(parseKind) && extractedText != null
                && !extractedText.isBlank();
    }
}
