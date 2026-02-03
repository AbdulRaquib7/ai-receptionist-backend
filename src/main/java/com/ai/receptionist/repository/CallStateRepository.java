package com.ai.receptionist.repository;

import com.ai.receptionist.entity.CallStateEntity;
import com.ai.receptionist.utils.ConversationState;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CallStateRepository extends JpaRepository<CallStateEntity, Long> {
    Optional<CallStateEntity> findByCallSid(String callSid);
}
