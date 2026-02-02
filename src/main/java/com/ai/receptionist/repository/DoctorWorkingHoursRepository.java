package com.ai.receptionist.repository;

import com.ai.receptionist.entity.DoctorWorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {
    List<DoctorWorkingHours> findByDoctorIdOrderByDayOfWeekAscStartTimeAsc(Long doctorId);
}
