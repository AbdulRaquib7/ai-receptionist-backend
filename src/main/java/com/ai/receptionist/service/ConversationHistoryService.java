package com.ai.receptionist.service;

import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.entity.ConversationHistory;
import com.ai.receptionist.repository.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConversationHistoryService {

    private final ConversationHistoryRepository repository;

    @Transactional(readOnly = true)
    public List<ChatMessage> getHistory(String callSid) {
        return repository.findByCallSidOrderByCreatedAtAsc(callSid).stream()
                .map(h -> new ChatMessage(h.getRole(), h.getContent()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void append(String callSid, String twilioPhone, String role, String content) {
        repository.save(ConversationHistory.builder()
                .callSid(callSid)
                .twilioPhone(twilioPhone)
                .role(role)
                .content(content)
                .build());
    }
}
