package com.ai.receptionist.repository;

import com.ai.receptionist.entity.ConversationMessage;

import jakarta.transaction.Transactional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {
	List<ConversationMessage> findByCallSidOrderByCreatedAtAsc(String callSid);

	@Transactional
	@Modifying
	@Query("DELETE FROM ConversationMessage m WHERE m.callSid = :callSid")
	void deleteByCallSid(@Param("callSid") String callSid);

}
