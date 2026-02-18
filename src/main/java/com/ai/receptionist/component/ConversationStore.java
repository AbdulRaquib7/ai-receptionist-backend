package com.ai.receptionist.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.service.ConversationHistoryService;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);
    private final Map<String, List<ChatMessage>> conversations = new ConcurrentHashMap<>();
    private final ConversationHistoryService historyService;

    public ConversationStore(ConversationHistoryService historyService) {
        this.historyService = historyService;
    }

    public List<ChatMessage> getHistory(String callSid) {
        return conversations.getOrDefault(callSid, Collections.emptyList());
    }

    public void appendUser(String callSid, String text) {
        append(callSid, null, "user", text);
        log.info("[{}] User: {}", callSid, text);
    }

    public void appendUser(String callSid, String twilioPhone, String text) {
        append(callSid, StringUtils.isNotBlank(twilioPhone) ? twilioPhone : null, "user", text);
        log.info("[{}] User: {}", callSid, text);
    }

    public void appendAssistant(String callSid, String text) {
        append(callSid, null, "assistant", text);
        log.info("[{}] Assistant: {}", callSid, text);
    }

    public void appendAssistant(String callSid, String twilioPhone, String text) {
        append(callSid, StringUtils.isNotBlank(twilioPhone) ? twilioPhone : null, "assistant", text);
        log.info("[{}] Assistant: {}", callSid, text);
    }

    private void append(String callSid, String twilioPhone, String role, String text) {
        conversations.computeIfAbsent(callSid, key -> new ArrayList<>()).add(new ChatMessage(role, text));
        try {
            historyService.append(callSid, twilioPhone, role, text);
        } catch (Exception e) {
            log.warn("Failed to persist conversation history", e);
        }
    }

    public void clear(String callSid) {
        conversations.remove(callSid);
    }
    
    public List<String> getConversationSummary(String callSid) {

        List<ChatMessage> history = getHistory(callSid);
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> summary = new ArrayList<>();

        int start = Math.max(0, history.size() - 6);

        for (int i = start; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            summary.add(m.getRole() + ": " + m.getContent());
        }

        return summary;
    }

}
