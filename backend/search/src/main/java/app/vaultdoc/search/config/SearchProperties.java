package app.vaultdoc.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vaultdoc.search")
public record SearchProperties(
        /** Name of the Elasticsearch index. */
        String indexName,
        /**
         * "true" or "wait_for" forces ES to make the change visible before the
         * call returns — required for our ITs. "false" (default) is async.
         */
        String refreshPolicy) {

    public SearchProperties {
        if (indexName == null || indexName.isBlank()) {
            indexName = "vaultdoc-documents-v1";
        }
        if (refreshPolicy == null || refreshPolicy.isBlank()) {
            refreshPolicy = "false";
        }
    }
}
