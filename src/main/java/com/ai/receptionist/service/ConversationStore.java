package com.ai.receptionist.service;

import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.entity.ConversationMessage;
import com.ai.receptionist.repository.ConversationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DB-backed storage for per-call conversation history.
 * Replaces in-memory storage while preserving the same interface.
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    private final ConversationMessageRepository repository;

    public ConversationStore(ConversationMessageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> getHistory(String callSid) {
        if (callSid == null || callSid.isEmpty()) return Collections.emptyList();
        return repository.findByCallSidOrderByCreatedAtAsc(callSid).stream()
                .map(m -> new ChatMessage(m.getRole(), m.getContent()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void appendUser(String callSid, String text) {
        append(callSid, "user", text);
        log.info("[{}] User: {}", callSid, text);
    }

    @Transactional
    public void appendAssistant(String callSid, String text) {
        append(callSid, "assistant", text);
        log.info("[{}] Assistant: {}", callSid, text);
    }

    private void append(String callSid, String role, String content) {
        if (callSid == null || content == null) return;
        ConversationMessage msg = ConversationMessage.builder()
                .callSid(callSid)
                .role(role)
                .content(content.length() > 4000 ? content.substring(0, 4000) : content)
                .build();
        repository.save(msg);
    }

    @Transactional
    public void clear(String callSid) {
        if (callSid == null) return;
        repository.deleteByCallSid(callSid);
    }
}
