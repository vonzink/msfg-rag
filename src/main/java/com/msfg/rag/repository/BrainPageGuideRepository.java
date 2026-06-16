package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainPageGuide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BrainPageGuideRepository extends JpaRepository<BrainPageGuide, UUID> {

    List<BrainPageGuide> findAllByOrderByCreatedAtDescIdDesc();

    List<BrainPageGuide> findByActiveTrueOrderByCreatedAtDescIdDesc();

    long countByActiveTrue();
}
