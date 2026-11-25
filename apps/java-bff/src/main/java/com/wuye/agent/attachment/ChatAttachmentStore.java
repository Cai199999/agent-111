package com.wuye.agent.attachment;

import java.util.Optional;

public interface ChatAttachmentStore {

    void insert(ChatAttachmentRecord record);

    Optional<ChatAttachmentRecord> findByNo(String attachmentNo);
}
