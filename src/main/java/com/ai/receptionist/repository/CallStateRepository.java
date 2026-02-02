package com.ai.receptionist.repository;

import com.ai.receptionist.entity.CallStateEntity;
import com.ai.receptionist.entity.ConversationState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CallStateRepository extends JpaRepository<CallStateEntity, Long> {
    Optional<CallStateEntity> findByCallSid(String callSid);
}
