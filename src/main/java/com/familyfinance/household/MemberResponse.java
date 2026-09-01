package com.familyfinance.household;

import java.time.Instant;

public record MemberResponse(Long id, String name, String roleLabel, Instant createdAt) {

    static MemberResponse from(FamilyMember member) {
        return new MemberResponse(member.getId(), member.getName(), member.getRoleLabel(), member.getCreatedAt());
    }
}
