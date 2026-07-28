package com.cityseek.controller;

import com.cityseek.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final Set<String> ALLOWED_SUFFIXES = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private final Path uploadRoot;

    public UploadController(@Value("${app.upload-dir:./data/images}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PostMapping("/blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("请选择要上传的图片");
        }
        String suffix = suffixOf(image.getOriginalFilename());
        if (!ALLOWED_SUFFIXES.contains(suffix)) {
            return Result.fail("仅支持 jpg、jpeg、png、webp、gif 图片");
        }

        String relativeName = createNewFileName(suffix);
        Path destination = resolveSafely(relativeName);
        if (destination == null) {
            return Result.fail("非法文件路径");
        }
        try {
            Files.createDirectories(destination.getParent());
            image.transferTo(destination);
            String publicPath = "/" + relativeName.replace('\\', '/');
            log.info("Image uploaded: {}", publicPath);
            return Result.ok(publicPath);
        } catch (IOException e) {
            throw new IllegalStateException("文件上传失败", e);
        }
    }

    @DeleteMapping("/blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        if (filename == null || filename.isBlank()) {
            return Result.fail("错误的文件名称");
        }
        String relativeName = filename.replace('\\', '/');
        while (relativeName.startsWith("/")) {
            relativeName = relativeName.substring(1);
        }
        Path target = resolveSafely(relativeName);
        if (target == null || Files.isDirectory(target)) {
            return Result.fail("错误的文件名称");
        }
        try {
            return Files.deleteIfExists(target) ? Result.ok() : Result.fail("文件不存在");
        } catch (IOException e) {
            throw new IllegalStateException("文件删除失败", e);
        }
    }

    private Path resolveSafely(String relativeName) {
        Path target = uploadRoot.resolve(relativeName).normalize();
        return target.startsWith(uploadRoot) ? target : null;
    }

    private String createNewFileName(String suffix) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        return "blogs/" + (hash & 0xF) + "/" + ((hash >> 4) & 0xF) + "/" + name + "." + suffix;
    }

    private String suffixOf(String filename) {
        int index = filename == null ? -1 : filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
