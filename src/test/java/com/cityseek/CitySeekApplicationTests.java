package com.cityseek;

import com.cityseek.dto.LoginFormDTO;
import com.cityseek.dto.UserProfileUpdateDTO;
import com.cityseek.utils.RegexUtils;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitySeekApplicationTests {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validatesPhoneAndCodeFormats() {
        assertFalse(RegexUtils.isPhoneInvalid("13800138000"));
        assertTrue(RegexUtils.isPhoneInvalid("12345"));
        assertTrue(RegexUtils.isPhoneInvalid(""));

        assertFalse(RegexUtils.isCodeInvalid("A1b2C3"));
        assertTrue(RegexUtils.isCodeInvalid("123"));
    }

    @Test
    void rejectsInvalidLoginForm() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("12345");
        form.setCode("12");
        assertFalse(validator.validate(form).isEmpty());

        form.setPhone("13800138000");
        form.setCode("123456");
        assertTrue(validator.validate(form).isEmpty());
    }

    @Test
    void validatesProfileUpdate() {
        UserProfileUpdateDTO profile = new UserProfileUpdateDTO();
        profile.setNickName("测试用户");
        profile.setIntroduce("个人介绍");
        profile.setCity("杭州");
        profile.setBirthday(LocalDate.now());
        assertTrue(validator.validate(profile).isEmpty());

        profile.setNickName(" ");
        profile.setBirthday(LocalDate.now().plusDays(1));
        assertFalse(validator.validate(profile).isEmpty());
    }
}
