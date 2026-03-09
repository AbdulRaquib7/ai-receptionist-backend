package com.ai.receptionist.component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ai.receptionist.config.ConversationProperties;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.service.ConversationHistoryService;
import com.ai.receptionist.utils.LogSanitizer;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    /** TTL-bounded cache: max 1000 conversations, auto-expire after 2 hours of inactivity */
    private final Cache<String, List<ChatMessage>> conversations = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();

    private final ConversationHistoryService historyService;
    private final ConversationProperties conversationProps;

    public ConversationStore(ConversationHistoryService historyService,
                             ConversationProperties conversationProps) {
        this.historyService = historyService;
        this.conversationProps = conversationProps;
    }

    /**
     * Returns conversation history for the call. If in-memory is empty (e.g. after stream
     * reconnect or server restart), hydrates from conversation_history table so the flow
     * can resume seamlessly without losing context.
     */
    public List<ChatMessage> getHistory(String callSid) {
        List<ChatMessage> inMemory = conversations.getIfPresent(callSid);
        if (inMemory != null && !inMemory.isEmpty()) {
            return inMemory;
        }
        List<ChatMessage> fromDb = historyService.getHistory(callSid);
        if (!fromDb.isEmpty()) {
            conversations.put(callSid, Collections.synchronizedList(new ArrayList<>(fromDb)));
            log.info("[{}] Resumed conversation from DB ({} messages)", callSid, fromDb.size());
        }
        List<ChatMessage> result = conversations.getIfPresent(callSid);
        return result != null ? result : Collections.emptyList();
    }

    public void appendUser(String callSid, String text) {
        append(callSid, null, "user", text);
        log.debug("[{}] User: {}", callSid, text);
        log.info("[{}] User: [{}]", callSid, LogSanitizer.truncateText(text));
    }

    public void appendUser(String callSid, String twilioPhone, String text) {
        append(callSid, StringUtils.isNotBlank(twilioPhone) ? twilioPhone : null, "user", text);
        log.debug("[{}] User: {}", callSid, text);
        log.info("[{}] User: [{}]", callSid, LogSanitizer.truncateText(text));
    }

    public void appendAssistant(String callSid, String text) {
        append(callSid, null, "assistant", text);
        log.debug("[{}] Assistant: {}", callSid, text);
        log.info("[{}] Assistant: [{}]", callSid, LogSanitizer.truncateText(text));
    }

    public void appendAssistant(String callSid, String twilioPhone, String text) {
        append(callSid, StringUtils.isNotBlank(twilioPhone) ? twilioPhone : null, "assistant", text);
        log.debug("[{}] Assistant: {}", callSid, text);
        log.info("[{}] Assistant: [{}]", callSid, LogSanitizer.truncateText(text));
    }

    private void append(String callSid, String twilioPhone, String role, String text) {
        conversations.get(callSid, key -> Collections.synchronizedList(new ArrayList<>()))
                .add(new ChatMessage(role, text));
        try {
            historyService.append(callSid, twilioPhone, role, text);
        } catch (Exception e) {
            log.warn("Failed to persist conversation history", e);
        }
    }

    public void clear(String callSid) {
        conversations.invalidate(callSid);
    }

    public List<String> getConversationSummary(String callSid) {

        List<ChatMessage> history = getHistory(callSid);
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }

        // Snapshot to avoid ConcurrentModificationException on synchronized lists
        List<ChatMessage> snapshot = new ArrayList<>(history);
        List<String> summary = new ArrayList<>();

        int start = Math.max(0, snapshot.size() - conversationProps.getRecentMessageWindow());

        for (int i = start; i < snapshot.size(); i++) {
            ChatMessage m = snapshot.get(i);
            summary.add(m.getRole() + ": " + m.getContent());
        }

        return summary;
    }

}
