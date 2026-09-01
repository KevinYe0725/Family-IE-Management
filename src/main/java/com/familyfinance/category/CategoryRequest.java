package com.familyfinance.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
        @NotNull(message = "收支类型不能为空")
        TransactionKind kind,
        @NotBlank(message = "分类名称不能为空")
        @Size(max = 30, message = "分类名称长度不能超过 30 个字符")
        String name,
        @NotBlank(message = "分类颜色不能为空")
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "分类颜色必须是 #RRGGBB 格式")
        String color) {
}
