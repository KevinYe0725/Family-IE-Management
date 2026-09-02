package com.familyfinance.category;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.CurrentHousehold;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final CurrentHousehold currentHousehold;

    public CategoryController(CategoryService categoryService, CurrentHousehold currentHousehold) {
        this.categoryService = categoryService;
        this.currentHousehold = currentHousehold;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<List<CategoryResponse>>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "flat") String projection,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CategoryPage result = categoryService.list(currentHousehold.id(authentication), projection, page, size);
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.size()))
                .header("X-Total-Elements", Long.toString(result.totalElements()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .header("X-Has-Next", Boolean.toString(result.hasNext()))
                .body(ApiEnvelope.data(result.items()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<CategoryResponse> create(Authentication authentication, @Valid @RequestBody CategoryRequest request) {
        return ApiEnvelope.data(categoryService.create(authentication, request));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<CategoryResponse> update(
            Authentication authentication,
            @PathVariable long id,
            @Valid @RequestBody CategoryRequest request) {
        return ApiEnvelope.data(categoryService.update(authentication, id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication authentication, @PathVariable long id) {
        categoryService.delete(authentication, id);
    }
}
