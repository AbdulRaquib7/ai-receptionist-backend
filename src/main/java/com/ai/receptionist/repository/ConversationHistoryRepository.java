package com.ai.receptionist.repository;

import com.ai.receptionist.entity.ConversationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationHistoryRepository extends JpaRepository<ConversationHistory, Long> {

    List<ConversationHistory> findByCallSidOrderByCreatedAtAsc(String callSid);
}
