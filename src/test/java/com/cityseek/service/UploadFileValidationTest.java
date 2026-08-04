package com.cityseek.service;

import com.cityseek.dto.UserDTO;
import com.cityseek.security.UserRole;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadFileValidationTest {

    @TempDir
    Path uploadDirectory;

    @Test
    void rejectsTraversalBeforeLookingUpMetadata() {
        UploadFileService service = new UploadFileService(uploadDirectory.toString());
        UserDTO admin = new UserDTO();
        admin.setId(1L);
        admin.setRole(UserRole.ADMIN);

        assertThrows(IllegalArgumentException.class,
                () -> service.deleteBlogImage("/blogs/../outside.png", admin));
    }

    @Test
    void rejectsFakeImageContentDespiteAllowedSuffixAndMimeType() {
        UploadFileService service = new UploadFileService(uploadDirectory.toString());
        MockMultipartFile fakeImage = new MockMultipartFile(
                "file", "fake.png", "image/png", "not-an-image".getBytes());

        assertThrows(IllegalArgumentException.class,
                () -> service.uploadBlogImage(fakeImage, 1L));
    }
}
