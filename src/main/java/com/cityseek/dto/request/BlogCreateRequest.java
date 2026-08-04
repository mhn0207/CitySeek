package com.cityseek.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BlogCreateRequest {

    @NotNull(message = "商铺不能为空")
    private Long shopId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题不能超过255个字符")
    private String title;

    @NotBlank(message = "图片不能为空")
    @Size(max = 2048, message = "图片地址不能超过2048个字符")
    private String images;

    @NotBlank(message = "内容不能为空")
    @Size(max = 2048, message = "内容不能超过2048个字符")
    private String content;
}
