package com.ai.receptionist.repository;

import com.ai.receptionist.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {
    List<ConversationMessage> findByCallSidOrderByCreatedAtAsc(String callSid);

    @Modifying
    @Query("DELETE FROM ConversationMessage m WHERE m.callSid = :callSid")
    void deleteByCallSid(@Param("callSid") String callSid);
}
