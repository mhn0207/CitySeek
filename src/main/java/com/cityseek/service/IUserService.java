package com.cityseek.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cityseek.dto.LoginFormDTO;
import com.cityseek.dto.Result;
import com.cityseek.dto.UserProfileUpdateDTO;
import com.cityseek.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);

    Result logout(String authorization);

    Result updateProfile(UserProfileUpdateDTO profile, String authorization);

    Result sign();

    Result signCount();

}
