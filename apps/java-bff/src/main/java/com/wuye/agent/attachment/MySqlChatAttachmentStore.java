package com.wuye.agent.attachment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MySqlChatAttachmentStore implements ChatAttachmentStore {

    private final JdbcTemplate jdbc;

    public MySqlChatAttachmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ChatAttachmentRecord record) {
        jdbc.update("""
                        INSERT INTO wa_chat_attachment
                            (attachment_no, session_no, uploader_actor_id, file_name,
                             content_type, size_bytes, sha256, storage_path,
                             parse_kind, parse_status, page_count, text_chars,
                             extracted_text, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                record.attachmentNo(), record.sessionNo(), record.uploaderActorId(),
                record.fileName(), record.contentType(), record.sizeBytes(), record.sha256(),
                record.storagePath(), record.parseKind(), record.parseStatus(),
                record.pageCount(), record.textChars(), record.extractedText(),
                Timestamp.from(record.createdAt()));
    }

    @Override
    public Optional<ChatAttachmentRecord> findByNo(String attachmentNo) {
        List<ChatAttachmentRecord> rows = jdbc.query(
                "SELECT * FROM wa_chat_attachment WHERE attachment_no = ?",
                (rs, rowNum) -> map(rs), attachmentNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private ChatAttachmentRecord map(ResultSet rs) throws SQLException {
        return new ChatAttachmentRecord(
                rs.getString("attachment_no"),
                rs.getString("session_no"),
                rs.getString("uploader_actor_id"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("storage_path"),
                rs.getString("parse_kind"),
                rs.getString("parse_status"),
                (Integer) rs.getObject("page_count"),
                rs.getInt("text_chars"),
                rs.getString("extracted_text"),
                rs.getTimestamp("created_at").toInstant());
    }
}
