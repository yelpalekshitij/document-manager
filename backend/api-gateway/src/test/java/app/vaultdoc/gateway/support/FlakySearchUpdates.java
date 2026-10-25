package app.vaultdoc.gateway.support;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import app.vaultdoc.search.domain.DocumentIndexRecord;
import app.vaultdoc.search.domain.DocumentTagsUpdate;
import app.vaultdoc.search.service.SearchPage;
import app.vaultdoc.search.service.SearchService;

/**
 * Wraps the real {@link SearchService} so a test can make the next N
 * {@code updateTags} calls fail, as if Elasticsearch were down. A plain
 * decorator (not a Mockito spy) because the OCR consumer and request threads
 * call the bean concurrently while the test arms it.
 */
public class FlakySearchUpdates implements SearchService {

    private final SearchService delegate;
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    public FlakySearchUpdates(SearchService delegate) {
        this.delegate = delegate;
    }

    public void failNextTagUpdates(int n) {
        failNext.set(n);
    }

    public int failures() {
        return failures.get();
    }

    @Override
    public void index(DocumentIndexRecord record) {
        delegate.index(record);
    }

    @Override
    public void updateTags(UUID userId, List<DocumentTagsUpdate> updates) {
        if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            failures.incrementAndGet();
            throw new IllegalStateException("simulated Elasticsearch outage");
        }
        delegate.updateTags(userId, updates);
    }

    @Override
    public void delete(UUID userId, UUID documentId) {
        delegate.delete(userId, documentId);
    }

    @Override
    public SearchPage search(UUID userId, String query, UUID folderId, Set<UUID> tagIds, int page, int pageSize) {
        return delegate.search(userId, query, folderId, tagIds, page, pageSize);
    }

    /** Import to wrap the application's SearchService bean. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        static BeanPostProcessor flakySearchWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof SearchService search && !(bean instanceof FlakySearchUpdates)
                            ? new FlakySearchUpdates(search)
                            : bean;
                }
            };
        }
    }
}
