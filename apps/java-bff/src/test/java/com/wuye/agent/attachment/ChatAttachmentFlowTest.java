package com.wuye.agent.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 聊天附件闭环：上传 → 主 Agent 解析/路由 → 合同子 Agent 审查 → 结果返回。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "wuye.attachment.root=target/test-chat-attachments")
class ChatAttachmentFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Test
    void contractTextAttachmentRoutesToContractAgent() throws Exception {
        String token = login("admin", "admin123");
        ChatAttachmentView attachment = upload(token, "物业服务合同.txt",
                "合同 甲方 乙方 付款 违约金 保密 验收 服务费".getBytes(StandardCharsets.UTF_8));
        assertEquals("SIMPLE_TEXT", attachment.parseKind());

        String sessionNo = createSession(token);
        String body = send(token, sessionNo, "请审查这份合同", attachment.attachmentNo());
        assertTrue(body.contains("contract.review"), body);
        assertTrue(body.contains("overall") || body.contains("findings"), body);
    }

    @Test
    void imageAttachmentAsksForVision() throws Exception {
        String token = login("admin", "admin123");
        ChatAttachmentView attachment = upload(token, "扫描件.png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertEquals("COMPLEX_MULTIMODAL", attachment.parseKind());

        String sessionNo = createSession(token);
        String body = send(token, sessionNo, "看看这个附件", attachment.attachmentNo());
        assertTrue(body.contains("NEEDS_VISION") || body.contains("视觉"), body);
    }

    private ChatAttachmentView upload(String token, String fileName, byte[] data) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        ResponseEntity<String> response = rest.postForEntity(
                url("/api/v1/chat/attachments"),
                new HttpEntity<>(form, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode payload = mapper.readTree(response.getBody()).path("data");
        return new ChatAttachmentView(
                payload.path("attachmentNo").asText(),
                payload.path("parseKind").asText());
    }

    private String createSession(String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        ResponseEntity<String> response = rest.postForEntity(
                url("/api/v1/chat/sessions"),
                new HttpEntity<>(Map.of("title", "attachment-test"), headers), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody()).path("data").path("sessionNo").asText();
    }

    private String send(String token, String sessionNo, String content, String attachmentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));
        headers.setBearerAuth(token);
        Map<String, Object> body = Map.of(
                "content", content,
                "clientMessageId", "att-" + System.nanoTime(),
                "attachmentId", attachmentId);
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/chat/sessions/" + sessionNo + "/messages"),
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return response.getBody();
    }

    private String login(String username, String password) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                url("/api/v1/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
                String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody()).path("data").path("accessToken").asText();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private record ChatAttachmentView(String attachmentNo, String parseKind) {
    }
}
