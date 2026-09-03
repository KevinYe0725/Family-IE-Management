package com.familyfinance.investment;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SecurityRepository extends JpaRepository<Security, Long> {

    Optional<Security> findByTsCodeAndActiveTrue(String tsCode);

    Optional<Security> findByIdAndActiveTrue(Long id);

    @Query("""
            select security from Security security
            where security.active = true
              and (upper(security.tsCode) like concat('%', :query, '%')
                   or upper(security.name) like concat('%', :query, '%'))
            """)
    Page<Security> search(@Param("query") String query, Pageable pageable);
}
