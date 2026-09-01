package com.familyfinance.transaction;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    static Specification<FinancialTransaction> matching(TransactionCriteria criteria) {
        return (root, query, builder) -> {
            root.fetch("member", JoinType.LEFT);
            root.fetch("category", JoinType.LEFT);
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("household").get("id"), criteria.householdId()));
            if (criteria.kind() != null) {
                predicates.add(builder.equal(root.get("kind"), criteria.kind()));
            }
            if (criteria.memberId() != null) {
                predicates.add(builder.equal(root.get("member").get("id"), criteria.memberId()));
            }
            if (criteria.categoryId() != null) {
                predicates.add(builder.equal(root.get("category").get("id"), criteria.categoryId()));
            }
            if (criteria.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("occurredOn"), criteria.from()));
            }
            if (criteria.to() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("occurredOn"), criteria.to()));
            }
            if (criteria.keyword() != null) {
                String pattern = "%" + criteria.keyword().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("merchant")), pattern),
                        builder.like(builder.lower(root.get("location")), pattern),
                        builder.like(builder.lower(root.get("note")), pattern),
                        builder.like(builder.lower(root.join("category").get("name")), pattern),
                        builder.like(builder.lower(root.join("member").get("name")), pattern)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
