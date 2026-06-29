package app.vaultdoc.search.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import app.vaultdoc.search.service.ElasticsearchSearchService;
import app.vaultdoc.search.service.SearchService;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SearchService searchService(ElasticsearchClient client, SearchProperties properties) {
        return new ElasticsearchSearchService(client, properties.indexName(), properties.refreshPolicy());
    }
}
