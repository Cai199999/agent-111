package com.wuye.agent.attachment;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!mysql")
public class InMemoryChatAttachmentStore implements ChatAttachmentStore {

    private final Map<String, ChatAttachmentRecord> records = new ConcurrentHashMap<>();

    @Override
    public void insert(ChatAttachmentRecord record) {
        records.put(record.attachmentNo(), record);
    }

    @Override
    public Optional<ChatAttachmentRecord> findByNo(String attachmentNo) {
        return Optional.ofNullable(records.get(attachmentNo));
    }
}
