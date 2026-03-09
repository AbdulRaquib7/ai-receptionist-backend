package com.ai.receptionist.component;

import com.ai.receptionist.config.ConversationProperties;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.service.ConversationHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

class ConversationStoreTest {

    @Mock
    private ConversationHistoryService historyService;

    private ConversationStore store;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ConversationProperties props = new ConversationProperties();
        store = new ConversationStore(historyService, props);
    }

    @Test
    void shouldAppendUserAndAssistantMessages() {
        String callSid = "call-123";
        store.appendUser(callSid, "Hello");
        store.appendAssistant(callSid, "Hi there");

        List<ChatMessage> history = store.getHistory(callSid);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRole()).isEqualTo("user");
        assertThat(history.get(0).getContent()).isEqualTo("Hello");
        assertThat(history.get(1).getRole()).isEqualTo("assistant");
        assertThat(history.get(1).getContent()).isEqualTo("Hi there");

        verify(historyService).append(eq(callSid), eq(null), eq("user"), eq("Hello"));
        verify(historyService).append(eq(callSid), eq(null), eq("assistant"), eq("Hi there"));
    }

    @Test
    void shouldClearHistory() {
        String callSid = "call-123";
        store.appendUser(callSid, "Hello");
        store.clear(callSid);

        assertThat(store.getHistory(callSid)).isEmpty();
    }

    @Test
    void shouldProvideSummaryOfLastMessages() {
        String callSid = "call-123";
        for (int i = 0; i < 10; i++) {
            store.appendUser(callSid, "Message " + i);
        }

        List<String> summary = store.getConversationSummary(callSid);
        // summary should only include last 6 messages (default recentMessageWindow)
        assertThat(summary).hasSize(6);
        assertThat(summary.get(5)).isEqualTo("user: Message 9");
        assertThat(summary.get(0)).isEqualTo("user: Message 4");
    }

    @Test
    void shouldAppendUserWithTwilioPhone() {
        String callSid = "call-456";
        store.appendUser(callSid, "+1234567890", "Hello from phone");

        List<ChatMessage> history = store.getHistory(callSid);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getContent()).isEqualTo("Hello from phone");

        verify(historyService).append(eq(callSid), eq("+1234567890"), eq("user"), eq("Hello from phone"));
    }
}
