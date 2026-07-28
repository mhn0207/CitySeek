package com.cityseek.utils;

import cn.hutool.core.util.StrUtil;

public final class RegexUtils {

    private RegexUtils() {
    }

    public static boolean isPhoneInvalid(String phone) {
        return mismatch(phone, RegexPatterns.PHONE_REGEX);
    }

    public static boolean isEmailInvalid(String email) {
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    public static boolean isCodeInvalid(String code) {
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    private static boolean mismatch(String value, String regex) {
        if (StrUtil.isBlank(value)) {
            return true;
        }
        return !value.matches(regex);
    }
}
