package uz.minipolya.minipolya_backend.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Component;

/**
 * Counts the SQL statements Hibernate prepares — how a test proves a list costs a
 * fixed handful of queries rather than one per row. {@link #start} flushes and
 * clears the persistence context first, so rows a test just saved are read back
 * from the database rather than served from the first-level cache.
 */
@Component
public class QueryCounter {

    private final Statistics statistics;
    private final EntityManager entityManager;

    public QueryCounter(EntityManagerFactory entityManagerFactory, EntityManager entityManager) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        this.entityManager = entityManager;
    }

    public void start() {
        entityManager.flush();
        entityManager.clear();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    public long count() {
        return statistics.getPrepareStatementCount();
    }
}
