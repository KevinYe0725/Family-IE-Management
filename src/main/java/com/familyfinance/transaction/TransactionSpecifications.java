package com.familyfinance.transaction;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

final class TransactionSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private TransactionSpecifications() {
    }

    static Specification<FinancialTransaction> matching(TransactionCriteria criteria) {
        return (root, query, builder) -> {
            if (FinancialTransaction.class.equals(query.getResultType())) {
                root.fetch("member", JoinType.LEFT);
                root.fetch("category", JoinType.LEFT).fetch("parent", JoinType.LEFT);
                root.fetch("account", JoinType.LEFT);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("household").get("id"), criteria.householdId()));
            if (criteria.kind() != null) {
                predicates.add(builder.equal(root.get("kind"), criteria.kind()));
            }
            if (criteria.accountId() != null) {
                predicates.add(builder.equal(root.get("account").get("id"), criteria.accountId()));
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
                String pattern = "%" + escapeLike(criteria.keyword().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("merchant")), pattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.get("location")), pattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.get("note")), pattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.join("category").get("name")), pattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.join("member").get("name")), pattern, LIKE_ESCAPE)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String keyword) {
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
