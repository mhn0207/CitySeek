package com.cityseek.integration;

import com.cityseek.dto.UserDTO;
import com.cityseek.exception.BusinessException;
import com.cityseek.exception.ForbiddenException;
import com.cityseek.security.UserRole;
import com.cityseek.service.UploadFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class UploadOwnershipIntegrationTest extends ContainerIntegrationTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZL9sAAAAASUVORK5CYII=");

    @Autowired
    private UploadFileService uploadFileService;

    @Test
    void enforcesOwnerAndBoundImageDeletionRules() {
        assertThrows(IllegalArgumentException.class, () -> uploadFileService.deleteBlogImage(
                "/blogs/../outside.png", user(2L, UserRole.ADMIN)));
        String temporary = uploadFileService.uploadBlogImage(image("temporary.png"), 1L);
        UserDTO stranger = user(2L, UserRole.USER);
        assertThrows(ForbiddenException.class,
                () -> uploadFileService.deleteBlogImage(temporary, stranger));
        uploadFileService.deleteBlogImage(temporary, user(1L, UserRole.USER));

        String bound = uploadFileService.uploadBlogImage(image("bound.png"), 1L);
        uploadFileService.bindBlogImages(bound, 1L);
        assertThrows(BusinessException.class,
                () -> uploadFileService.deleteBlogImage(bound, user(1L, UserRole.USER)));
        uploadFileService.deleteBlogImage(bound, user(2L, UserRole.ADMIN));
    }

    private MockMultipartFile image(String name) {
        return new MockMultipartFile("file", name, "image/png", PNG);
    }

    private UserDTO user(long id, UserRole role) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
