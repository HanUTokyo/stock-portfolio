package com.stockportfolio.repository;

import com.stockportfolio.model.MobileSyncMutation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MobileSyncMutationRepository extends JpaRepository<MobileSyncMutation, String> {
}
