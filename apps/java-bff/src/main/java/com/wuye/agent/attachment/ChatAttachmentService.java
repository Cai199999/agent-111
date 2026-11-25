package com.wuye.agent.attachment;

import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.common.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 聊天附件入口：存储 + 通用解析 + 领域提示。
 *
 * <p>只做“文件解析”，不做 OA/合同业务判断；业务判断交给子 Agent。</p>
 */
@Service
public class ChatAttachmentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "txt", "md", "csv", "png", "jpg", "jpeg");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv");

    private static final List<String> CONTRACT_KEYWORDS = List.of(
            "合同", "甲方", "乙方", "违约", "付款", "验收", "保密", "租赁",
            "服务费", "签署", "盖章", "条款", "保证金", "续签");
    private static final List<String> OA_KEYWORDS = List.of(
            "请假", "年假", "事假", "病假", "调休", "会议室", "用印", "公章",
            "采购", "维修", "公告", "通知", "归档", "请示", "报告", "审批");

    private final ChatAttachmentStore store;
    private final long maxBytes;
    private final Path root;

    public ChatAttachmentService(
            ChatAttachmentStore store,
            @Value("${wuye.attachment.root:data/chat-attachments}") String root,
            @Value("${wuye.attachment.max-bytes:10485760}") long maxBytes) {
        this.store = store;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public ChatAttachmentRecord upload(MultipartFile file, String sessionNo, AuthUser user) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("ATTACHMENT_EMPTY", "附件为空");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException("ATTACHMENT_TOO_LARGE", 413, "附件超过大小限制");
        }
        String original = safeName(file.getOriginalFilename());
        String ext = extension(original);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw ApiException.badRequest("ATTACHMENT_TYPE_REJECTED",
                    "不支持的文件类型：" + (ext.isBlank() ? "无扩展名" : ext));
        }
        try {
            byte[] data = file.getBytes();
            String sha256 = sha256(data);
            ParseResult parsed = parse(ext, data);
            String relative = relativePath(sha256, ext);
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) {
                throw ApiException.badRequest("ATTACHMENT_PATH_INVALID", "附件路径非法");
            }
            Files.createDirectories(target.getParent());
            Files.write(target, data);

            ChatAttachmentRecord record = new ChatAttachmentRecord(
                    "att_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                    sessionNo,
                    user.actorId(),
                    original,
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                    data.length,
                    sha256,
                    relative,
                    parsed.kind(),
                    parsed.status(),
                    parsed.pageCount(),
                    parsed.text() == null ? 0 : parsed.text().length(),
                    parsed.text(),
                    Instant.now());
            store.insert(record);
            return record;
        } catch (IOException ex) {
            throw new ApiException("ATTACHMENT_STORAGE_ERROR", 503, "附件保存失败");
        }
    }

    public ChatAttachmentRecord require(String attachmentNo, AuthUser user) {
        ChatAttachmentRecord record = store.findByNo(attachmentNo)
                .orElseThrow(() -> ApiException.notFound("ATTACHMENT_NOT_FOUND", "附件不存在"));
        if (!record.uploaderActorId().equals(user.actorId()) && !user.isAdmin()) {
            throw new ApiException("AUTH_FORBIDDEN", 403, "无权访问该附件");
        }
        return record;
    }

    public ChatAttachmentContext context(String attachmentNo, String userText, AuthUser user) {
        ChatAttachmentRecord record = require(attachmentNo, user);
        DomainDecision domain = classify(userText, record.extractedText());
        return new ChatAttachmentContext(
                record.attachmentNo(),
                record.fileName(),
                record.parseKind(),
                record.parseStatus(),
                record.extractedText(),
                record.pageCount() == null ? 0 : record.pageCount(),
                domain);
    }

    public byte[] bytes(ChatAttachmentRecord record) {
        try {
            Path path = root.resolve(record.storagePath()).normalize();
            if (!path.startsWith(root)) {
                throw new ApiException("ATTACHMENT_PATH_INVALID", 400, "附件路径非法");
            }
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw ApiException.notFound("ATTACHMENT_FILE_MISSING", "附件文件不存在");
        }
    }

    public DomainDecision classify(String userText, String documentText) {
        String haystack = ((userText == null ? "" : userText) + "\n"
                + (documentText == null ? "" : documentText)).toLowerCase(Locale.ROOT);
        int contract = score(haystack, CONTRACT_KEYWORDS);
        int oa = score(haystack, OA_KEYWORDS);
        if (contract >= 2 && contract > oa) {
            return new DomainDecision("CONTRACT", "contract.review",
                    confidence(contract, oa), "检测到合同/条款关键词 " + contract + " 个");
        }
        if (oa >= 1 && oa > contract) {
            return new DomainDecision("OA", "oa.request.draft",
                    confidence(oa, contract), "检测到 OA 单据关键词 " + oa + " 个");
        }
        return new DomainDecision("UNKNOWN", null, 0.0,
                "无法从描述和附件内容判断业务领域");
    }

    private ParseResult parse(String ext, byte[] data) {
        if (TEXT_EXTENSIONS.contains(ext)) {
            return new ParseResult("SIMPLE_TEXT", "PARSED",
                    new String(data, StandardCharsets.UTF_8), null);
        }
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return new ParseResult("COMPLEX_MULTIMODAL", "NEEDS_VISION", null, null);
        }
        if ("pdf".equals(ext)) {
            try (PDDocument document = Loader.loadPDF(data)) {
                String text = new PDFTextStripper().getText(document).trim();
                int pages = document.getNumberOfPages();
                double charsPerPage = pages == 0 ? 0 : (double) text.length() / pages;
                if (text.length() >= 80 && charsPerPage >= 20) {
                    return new ParseResult("SIMPLE_TEXT", "PARSED", text, pages);
                }
                return new ParseResult("COMPLEX_MULTIMODAL", "NEEDS_VISION", text, pages);
            } catch (IOException ex) {
                return new ParseResult("FAILED", "FAILED", null, null);
            }
        }
        return new ParseResult("UNSUPPORTED", "FAILED", null, null);
    }

    private static int score(String haystack, List<String> keywords) {
        int score = 0;
        for (String keyword : keywords) {
            int index = 0;
            while ((index = haystack.indexOf(keyword, index)) >= 0) {
                score++;
                index += keyword.length();
            }
        }
        return score;
    }

    private static double confidence(int winner, int loser) {
        return Math.min(0.95, 0.55 + 0.08 * Math.max(1, winner - loser));
    }

    private static String safeName(String name) {
        String base = name == null ? "attachment" : Path.of(name).getFileName().toString();
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        return base.isBlank() ? "attachment" : base.substring(0, Math.min(180, base.length()));
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String relativePath(String sha256, String ext) {
        String month = DateTimeFormatter.ofPattern("yyyy/MM")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        return month + "/" + sha256 + "." + ext;
    }

    private static String sha256(byte[] data) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder();
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record ParseResult(
            String kind, String status, String text, Integer pageCount) {
    }
}
