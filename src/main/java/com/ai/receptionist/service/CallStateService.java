package com.ai.receptionist.service;

import com.ai.receptionist.entity.CallStateEntity;
import com.ai.receptionist.entity.ConversationState;
import com.ai.receptionist.repository.CallStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CallStateService {

    private static final Logger log = LoggerFactory.getLogger(CallStateService.class);

    private final CallStateRepository repository;

    public CallStateService(CallStateRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CallStateEntity getOrCreate(String callSid) {
        return repository.findByCallSid(callSid)
                .orElseGet(() -> createNew(callSid));
    }

    @Transactional
    public CallStateEntity createNew(String callSid) {
        CallStateEntity state = CallStateEntity.builder()
                .callSid(callSid)
                .state(ConversationState.GREETING)
                .build();
        return repository.save(state);
    }

    @Transactional
    public CallStateEntity updateState(String callSid, ConversationState newState) {
        CallStateEntity state = getOrCreate(callSid);
        state.setState(newState);
        return repository.save(state);
    }

    @Transactional
    public CallStateEntity updateSelectedDoctor(String callSid, Long doctorId) {
        CallStateEntity state = getOrCreate(callSid);
        state.setSelectedDoctorId(doctorId);
        state.setState(ConversationState.DOCTOR_SELECTED);
        return repository.save(state);
    }

    @Transactional
    public CallStateEntity updateSelectedSlot(String callSid, Long slotId) {
        CallStateEntity state = getOrCreate(callSid);
        state.setSelectedSlotId(slotId);
        state.setState(ConversationState.USER_DETAILS);
        return repository.save(state);
    }

    @Transactional
    public CallStateEntity updatePatientDetails(String callSid, String name, String phone) {
        CallStateEntity state = getOrCreate(callSid);
        if (name != null) state.setPatientName(name);
        if (phone != null) state.setPatientPhone(phone);
        if (state.getPatientName() != null && state.getPatientPhone() != null) {
            state.setState(ConversationState.CONFIRMATION);
        }
        return repository.save(state);
    }

    @Transactional
    public CallStateEntity transition(String callSid, ConversationState newState) {
        CallStateEntity state = getOrCreate(callSid);
        state.setState(newState);
        return repository.save(state);
    }
}
