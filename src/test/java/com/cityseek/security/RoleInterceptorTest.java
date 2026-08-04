package com.cityseek.security;

import com.cityseek.dto.UserDTO;
import com.cityseek.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleInterceptorTest {

    private final RoleInterceptor interceptor = new RoleInterceptor();
    private final HandlerMethod adminHandler;

    RoleInterceptorTest() throws NoSuchMethodException {
        AdminEndpoint endpoint = new AdminEndpoint();
        adminHandler = new HandlerMethod(endpoint, AdminEndpoint.class.getMethod("invoke"));
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void deniesNormalUserAndAllowsAdministrator() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(1L);
        user.setRole(UserRole.USER);
        UserHolder.saveUser(user);
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), denied, adminHandler));
        assertEquals(403, denied.getStatus());

        user.setRole(UserRole.ADMIN);
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), allowed, adminHandler));
    }

    @Test
    void protectsActuatorEndpointsWithAdministratorRole() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(1L);
        user.setRole(UserRole.USER);
        UserHolder.saveUser(user);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        assertFalse(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        user.setRole(UserRole.ADMIN);
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }
    static class AdminEndpoint {
        @RequireRole(UserRole.ADMIN)
        public void invoke() {
        }
    }
}
