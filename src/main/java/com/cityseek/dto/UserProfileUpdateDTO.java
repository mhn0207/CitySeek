package com.cityseek.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UserProfileUpdateDTO {

    @NotBlank(message = "昵称不能为空")
    @Size(max = 32, message = "昵称不能超过32个字符")
    private String nickName;

    @Size(max = 255, message = "头像地址不能超过255个字符")
    private String icon;

    @Size(max = 128, message = "个人介绍不能超过128个字符")
    private String introduce;

    @Size(max = 64, message = "城市名称不能超过64个字符")
    private String city;

    private Boolean gender;

    @PastOrPresent(message = "生日不能晚于今天")
    private LocalDate birthday;
}