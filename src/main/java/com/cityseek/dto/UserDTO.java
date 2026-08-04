package com.cityseek.dto;

import com.cityseek.security.UserRole;
import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
    private UserRole role = UserRole.USER;
}
