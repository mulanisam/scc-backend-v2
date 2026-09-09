package com.app.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.app.entity.TradingEntry;

@Repository
public interface TradingEntryRepository extends JpaRepository<TradingEntry, Long> {
    // Add custom queries if needed
}
