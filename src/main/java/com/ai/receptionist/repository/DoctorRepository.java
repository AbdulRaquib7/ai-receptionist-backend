package com.ai.receptionist.repository;

import com.ai.receptionist.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {
	
    List<Doctor> findByActiveTrue();
    
    Optional<Doctor> findByKeyIgnoreCaseAndActiveTrue(String key);

}
