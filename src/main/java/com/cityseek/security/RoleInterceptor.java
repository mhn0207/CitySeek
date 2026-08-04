package com.cityseek.security;

import com.cityseek.dto.UserDTO;
import com.cityseek.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        UserRole requiredRole = actuatorRole(request);
        if (requiredRole == null && handler instanceof HandlerMethod handlerMethod) {
            RequireRole requirement = handlerMethod.getMethodAnnotation(RequireRole.class);
            if (requirement == null) {
                requirement = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
            }
            requiredRole = requirement == null ? null : requirement.value();
        }
        if (requiredRole == null) {
            return true;
        }

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录");
            return false;
        }
        UserRole actualRole = user.getRole() == null ? UserRole.USER : user.getRole();
        boolean allowed = requiredRole == UserRole.USER || actualRole == requiredRole;
        if (!allowed) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "权限不足");
            return false;
        }
        return true;
    }

    private UserRole actuatorRole(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/actuator") || path.startsWith("/actuator/") ? UserRole.ADMIN : null;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"errorMsg\":\"" + message + "\"}");
    }
}
