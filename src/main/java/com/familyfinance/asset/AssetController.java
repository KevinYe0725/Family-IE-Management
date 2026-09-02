package com.familyfinance.asset;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assets;
    private final AssetValuationService valuations;

    public AssetController(AssetService assets, AssetValuationService valuations) {
        this.assets = assets;
        this.valuations = valuations;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<AssetPage>> list(
            Authentication authentication,
            @RequestParam(required = false) AssetType type,
            @RequestParam(defaultValue = "ACTIVE") AssetStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AssetPage result = assets.list(authentication, type, status, page, size);
        return pageResponse(result, result.page(), result.size(), result.totalElements(), result.totalPages(), result.hasNext());
    }

    @GetMapping("/{id}")
    ApiEnvelope<AssetResponse> get(Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(assets.get(authentication, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<AssetResponse> create(Authentication authentication, @RequestBody AssetCreateRequest request) {
        return ApiEnvelope.data(assets.create(authentication, request));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<AssetResponse> update(
            Authentication authentication, @PathVariable long id, @RequestBody AssetPatchRequest request) {
        return ApiEnvelope.data(assets.update(authentication, id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(Authentication authentication, @PathVariable long id) {
        assets.archive(authentication, id);
    }

    @GetMapping("/{id}/valuations")
    ResponseEntity<ApiEnvelope<AssetValuationPage>> valuations(
            Authentication authentication,
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AssetValuationPage result = valuations.list(authentication, id, page, size);
        return pageResponse(result, result.page(), result.size(), result.totalElements(), result.totalPages(), result.hasNext());
    }

    @PostMapping("/{id}/valuations")
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<AssetValuationResponse> createValuation(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody AssetValuationRequest request) {
        return ApiEnvelope.data(valuations.create(authentication, id, request));
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> pageResponse(
            T body, int page, int size, long totalElements, int totalPages, boolean hasNext) {
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(page))
                .header("X-Page-Size", Integer.toString(size))
                .header("X-Total-Elements", Long.toString(totalElements))
                .header("X-Total-Pages", Integer.toString(totalPages))
                .header("X-Has-Next", Boolean.toString(hasNext))
                .body(ApiEnvelope.data(body));
    }
}
