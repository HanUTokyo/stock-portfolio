package com.stockportfolio.repository;

import com.stockportfolio.model.OverviewNote;
import com.stockportfolio.model.OverviewNoteType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OverviewNoteRepository extends JpaRepository<OverviewNote, Long> {
    Optional<OverviewNote> findByNoteType(OverviewNoteType noteType);
    List<OverviewNote> findAllByOrderByNoteTypeAsc();
}
