package com.ai.receptionist.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ai.receptionist.entity.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores per-call conversation history in memory keyed by callSid so that
 * after redirecting the call for TwiML Say, the new stream (same call) still has context.
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);
    private final Map<String, List<ChatMessage>> conversations = new ConcurrentHashMap<>();

    public List<ChatMessage> getHistory(String callSid) {
        return conversations.getOrDefault(callSid, Collections.emptyList());
    }

    public void appendUser(String callSid, String text) {
        append(callSid, new ChatMessage("user", text));
        log.info("[{}] User: {}", callSid, text);
    }

    public void appendAssistant(String callSid, String text) {
        append(callSid, new ChatMessage("assistant", text));
        log.info("[{}] Assistant: {}", callSid, text);
    }

    private void append(String callSid, ChatMessage message) {
        conversations.computeIfAbsent(callSid, key -> new ArrayList<>()).add(message);
    }

    public void clear(String callSid) {
        conversations.remove(callSid);
    }
}
