package com.interview.rag.repository;

import com.interview.rag.domain.QaRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface QaRecordRepository extends JpaRepository<QaRecord, Long> {

    List<QaRecord> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
