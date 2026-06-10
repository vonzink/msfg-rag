package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BrainSettingRepository extends JpaRepository<BrainSetting, String> {
}
