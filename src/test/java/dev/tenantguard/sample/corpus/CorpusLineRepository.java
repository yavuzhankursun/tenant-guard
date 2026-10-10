package dev.tenantguard.sample.corpus;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CorpusLineRepository extends JpaRepository<CorpusLine, Long> {

    /** Correct: lines filtered through the parent invoice's tenant. */
    @Query("select l.description from CorpusLine l join l.invoice i where i.tenantId = :tenantId order by l.id")
    List<String> descriptionsOfTenant(String tenantId);
}
