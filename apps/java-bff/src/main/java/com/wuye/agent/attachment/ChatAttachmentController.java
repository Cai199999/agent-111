package com.wuye.agent.attachment;

import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 聊天附件：上传、元数据、下载。文件只对上传人/管理员可见。
 */
@RestController
@RequestMapping("/api/v1/chat/attachments")
public class ChatAttachmentController {

    private final ChatAttachmentService service;
    private final Ids ids;

    public ChatAttachmentController(ChatAttachmentService service, Ids ids) {
        this.service = service;
        this.ids = ids;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionNo", required = false) String sessionNo,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        ChatAttachmentRecord record = service.upload(file, sessionNo, AuthContext.require());
        return ApiResponse.ok(view(record), trace(traceId));
    }

    @GetMapping("/{attachmentNo}")
    public ApiResponse<Map<String, Object>> metadata(
            @PathVariable String attachmentNo,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        ChatAttachmentRecord record = service.require(attachmentNo, AuthContext.require());
        return ApiResponse.ok(view(record), trace(traceId));
    }

    @GetMapping("/{attachmentNo}/download")
    public ResponseEntity<byte[]> download(@PathVariable String attachmentNo) {
        ChatAttachmentRecord record = service.require(attachmentNo, AuthContext.require());
        byte[] data = service.bytes(record);
        String encoded = URLEncoder.encode(record.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(data.length)
                .body(data);
    }

    private Map<String, Object> view(ChatAttachmentRecord record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attachmentNo", record.attachmentNo());
        data.put("sessionNo", record.sessionNo());
        data.put("fileName", record.fileName());
        data.put("contentType", record.contentType());
        data.put("sizeBytes", record.sizeBytes());
        data.put("sha256", record.sha256());
        data.put("parseKind", record.parseKind());
        data.put("parseStatus", record.parseStatus());
        data.put("pageCount", record.pageCount());
        data.put("textChars", record.textChars());
        data.put("createdAt", record.createdAt().toString());
        data.put("downloadUrl", "/api/v1/chat/attachments/" + record.attachmentNo() + "/download");
        return data;
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }
}
