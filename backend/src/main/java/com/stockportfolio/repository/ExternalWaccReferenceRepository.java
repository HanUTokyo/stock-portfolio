package com.stockportfolio.repository;
import com.stockportfolio.model.ExternalWaccReference;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ExternalWaccReferenceRepository extends JpaRepository<ExternalWaccReference, Long> {
    Optional<ExternalWaccReference> findBySymbolAndProvider(String symbol, String provider);
    List<ExternalWaccReference> findBySymbolOrderByProviderAsc(String symbol);
}
