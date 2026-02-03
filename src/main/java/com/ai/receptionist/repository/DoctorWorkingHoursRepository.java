package com.ai.receptionist.repository;

import com.ai.receptionist.entity.DoctorWorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {
    List<DoctorWorkingHours> findByDoctorIdOrderByDayOfWeekAscStartTimeAsc(Long doctorId);
}
