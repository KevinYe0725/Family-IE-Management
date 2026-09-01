package com.familyfinance.household;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemberRequest(
        @NotBlank(message = "成员姓名不能为空")
        @Size(max = 30, message = "成员姓名长度不能超过 30 个字符")
        String name,
        @Size(max = 30, message = "成员身份长度不能超过 30 个字符")
        String roleLabel) {
}
