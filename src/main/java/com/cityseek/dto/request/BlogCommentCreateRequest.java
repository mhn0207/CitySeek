package com.cityseek.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BlogCommentCreateRequest {

    @NotNull(message = "笔记不能为空")
    private Long blogId;

    @PositiveOrZero(message = "父评论编号不合法")
    private Long parentId;

    @PositiveOrZero(message = "回复评论编号不合法")
    private Long answerId;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 255, message = "评论内容不能超过255个字符")
    private String content;
}
