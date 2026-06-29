package app.vaultdoc.gateway.documents;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.vaultdoc.gateway.documents.dto.SearchHitDto;
import app.vaultdoc.gateway.documents.dto.SearchResponse;
import app.vaultdoc.search.service.SearchPage;
import app.vaultdoc.search.service.SearchService;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    public ResponseEntity<SearchResponse> search(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        SearchPage result = search.search(UUID.fromString(userId), q, folderId, page, pageSize);
        List<SearchHitDto> hits = result.hits().stream().map(SearchHitDto::from).toList();
        return ResponseEntity.ok(new SearchResponse(hits, result.totalHits(), result.page(), result.pageSize()));
    }
}
