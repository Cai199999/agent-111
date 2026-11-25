package com.wuye.agent.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.common.ApiException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class ChatAttachmentServiceTest {

    private static final AuthUser WORKER = new AuthUser(
            2L, "U0002", "worker", "物业普通员工", "10002", "0",
            Set.of("WORKER", "BASE"), Set.of());
    private static final AuthUser OTHER = new AuthUser(
            3L, "U0003", "user", "租户", "10003", "0",
            Set.of("USER", "BASE"), Set.of());

    @TempDir
    Path tempDir;

    private ChatAttachmentService service() {
        return new ChatAttachmentService(
                new InMemoryChatAttachmentStore(), tempDir.toString(), 1024 * 1024);
    }

    @Test
    void textAttachmentIsSimpleAndClassifiedAsContract() {
        ChatAttachmentService service = service();
        byte[] data = "合同 甲方 乙方 付款 违约金 保密".getBytes(StandardCharsets.UTF_8);
        ChatAttachmentRecord record = service.upload(
                new MockMultipartFile("file", "合同.txt", "text/plain", data),
                "ws_1", WORKER);

        assertEquals("SIMPLE_TEXT", record.parseKind());
        assertTrue(record.extractedText().contains("违约金"));
        ChatAttachmentContext context = service.context(
                record.attachmentNo(), "帮我审查合同", WORKER);
        assertEquals("CONTRACT", context.domain().domain());
        assertEquals("contract.review", context.domain().capabilityId());
    }

    @Test
    void imageIsComplexMultimodalAndNeedsVision() {
        ChatAttachmentService service = service();
        ChatAttachmentRecord record = service.upload(
                new MockMultipartFile("file", "扫描件.png", "image/png",
                        new byte[]{(byte) 0x89, 'P', 'N', 'G'}),
                null, WORKER);

        assertEquals("COMPLEX_MULTIMODAL", record.parseKind());
        assertEquals("NEEDS_VISION", record.parseStatus());
        ChatAttachmentContext context = service.context(
                record.attachmentNo(), "看看这个", WORKER);
        assertTrue(context.needsVision());
    }

    @Test
    void rejectedTypeAndOwnerOnlyDownload() {
        ChatAttachmentService service = service();
        assertThrows(ApiException.class, () -> service.upload(
                new MockMultipartFile("file", "evil.exe", "application/octet-stream",
                        new byte[]{'M', 'Z'}),
                null, WORKER));

        ChatAttachmentRecord record = service.upload(
                new MockMultipartFile("file", "说明.txt", "text/plain",
                        "请假说明".getBytes(StandardCharsets.UTF_8)),
                null, WORKER);

        ApiException forbidden = assertThrows(ApiException.class,
                () -> service.require(record.attachmentNo(), OTHER));
        assertEquals(403, forbidden.getHttpStatus());
        assertEquals("请假说明", new String(service.bytes(record), StandardCharsets.UTF_8));
    }

    @Test
    void pdfWithTextLayerIsSimpleText() throws Exception {
        ChatAttachmentService service = service();
        byte[] pdf = textPdf(
                "Property service contract with payment, acceptance, confidentiality and liability clauses.");
        ChatAttachmentRecord record = service.upload(
                new MockMultipartFile("file", "合同.pdf", "application/pdf", pdf),
                null, WORKER);

        assertEquals("SIMPLE_TEXT", record.parseKind());
        assertTrue(record.textChars() > 0);
        assertEquals(1, record.pageCount());
    }

    private static byte[] textPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
