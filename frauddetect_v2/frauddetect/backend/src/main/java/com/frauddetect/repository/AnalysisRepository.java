package com.frauddetect.repository;

import com.frauddetect.entity.Analysis;
import com.frauddetect.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AnalysisRepository extends JpaRepository<Analysis, Long> {
    List<Analysis> findByUserOrderByCreatedAtDesc(User user);
    long countByUser(User user);
}
