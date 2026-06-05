package com.msfg.rag.repository;

import com.msfg.rag.domain.MortgageDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MortgageDocumentRepository extends JpaRepository<MortgageDocument, UUID> {

    List<MortgageDocument> findByActiveTrue();
}
