package com.cityseek.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.UserDTO;
import com.cityseek.entity.UploadFile;
import com.cityseek.exception.BusinessException;
import com.cityseek.exception.ForbiddenException;
import com.cityseek.exception.ResourceNotFoundException;
import com.cityseek.mapper.UploadFileMapper;
import com.cityseek.security.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class UploadFileService extends ServiceImpl<UploadFileMapper, UploadFile> {

    private static final Set<String> ALLOWED_SUFFIXES = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");
    private static final long MAX_PIXELS = 40_000_000L;
    private final Path uploadRoot;

    public UploadFileService(@Value("${app.upload-dir:./data/images}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @Transactional
    public String uploadBlogImage(MultipartFile image, Long uploaderId) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片");
        }
        String suffix = suffixOf(image.getOriginalFilename());
        if (!ALLOWED_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("仅支持 jpg、jpeg、png、webp、gif 图片");
        }
        String contentType = image.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("文件 MIME 类型不是受支持的图片");
        }
        verifyImage(image, suffix);

        String relativeName = createRelativeName(suffix);
        Path destination = resolveRelative(relativeName);
        String publicPath = "/blogs/" + relativeName.replace('\\', '/');
        try {
            Files.createDirectories(destination.getParent());
            image.transferTo(destination);
            UploadFile metadata = new UploadFile()
                    .setPath(publicPath)
                    .setUploaderId(uploaderId)
                    .setBusinessType("BLOG")
                    .setStatus(UploadFile.TEMPORARY);
            if (!save(metadata)) {
                throw new IllegalStateException("图片元数据保存失败");
            }
            cleanFileIfTransactionRollsBack(destination);
            return publicPath;
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupException) {
                log.warn("Failed to clean up uploaded file {}", destination, cleanupException);
            }
            throw exception instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("文件上传失败", exception);
        }
    }

    @Transactional
    public void deleteBlogImage(String filename, UserDTO currentUser) {
        String publicPath = normalizePublicPath(filename);
        UploadFile metadata = query().eq("path", publicPath).one();
        if (metadata == null || Integer.valueOf(UploadFile.DELETED).equals(metadata.getStatus())) {
            throw new ResourceNotFoundException("文件不存在");
        }
        boolean admin = currentUser.getRole() == UserRole.ADMIN;
        if (!admin && !currentUser.getId().equals(metadata.getUploaderId())) {
            throw new ForbiddenException("不能删除他人上传的图片");
        }
        if (!admin && Integer.valueOf(UploadFile.BOUND).equals(metadata.getStatus())) {
            throw new BusinessException("已绑定到笔记的图片不能直接删除");
        }

        Path target = resolvePublicPath(publicPath);
        boolean updated = update().set("status", UploadFile.DELETED)
                .eq("id", metadata.getId())
                .ne("status", UploadFile.DELETED)
                .update();
        if (!updated) {
            throw new IllegalStateException("文件状态更新失败");
        }
        deleteFileAfterCommit(target, publicPath);
    }

    public void bindBlogImages(String imageList, Long uploaderId) {
        List<String> paths = parseImagePaths(imageList);
        List<UploadFile> files = paths.isEmpty() ? List.of()
                : query().in("path", paths).list();
        if (files.size() != paths.size()) {
            throw new BusinessException("笔记包含未登记的上传图片");
        }
        boolean invalid = files.stream().anyMatch(file ->
                !uploaderId.equals(file.getUploaderId())
                        || !Integer.valueOf(UploadFile.TEMPORARY).equals(file.getStatus()));
        if (invalid) {
            throw new ForbiddenException("只能发布自己尚未绑定的图片");
        }
        if (!files.isEmpty()) {
            int updated = getBaseMapper().update(null, new UpdateWrapper<UploadFile>()
                    .set("status", UploadFile.BOUND)
                    .in("id", files.stream().map(UploadFile::getId).toList())
                    .eq("uploader_id", uploaderId)
                    .eq("status", UploadFile.TEMPORARY));
            if (updated != files.size()) {
                throw new BusinessException("图片绑定状态已变化，请重试");
            }
        }
    }

    private void deleteFileAfterCommit(Path target, String publicPath) {
        Runnable deletion = () -> {
            try {
                Files.deleteIfExists(target);
            } catch (IOException exception) {
                log.error("Deleted metadata still has a physical file {}; cleanup is required", publicPath, exception);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletion.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deletion.run();
            }
        });
    }

    private void cleanFileIfTransactionRollsBack(Path destination) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    Files.deleteIfExists(destination);
                } catch (IOException exception) {
                    log.warn("Failed to clean up rolled-back upload {}", destination, exception);
                }
            }
        });
    }

    private void verifyImage(MultipartFile image, String suffix) {
        if ("webp".equals(suffix)) {
            try (InputStream input = image.getInputStream()) {
                byte[] header = input.readNBytes(12);
                boolean webp = header.length == 12
                        && Arrays.equals(Arrays.copyOfRange(header, 0, 4), new byte[]{'R', 'I', 'F', 'F'})
                        && Arrays.equals(Arrays.copyOfRange(header, 8, 12), new byte[]{'W', 'E', 'B', 'P'});
                if (!webp) {
                    throw new IllegalArgumentException("文件内容不是有效的 WebP 图片");
                }
                return;
            } catch (IOException exception) {
                throw new IllegalArgumentException("无法读取图片", exception);
            }
        }
        try (InputStream input = image.getInputStream();
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw new IllegalArgumentException("文件内容不是有效图片");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("文件内容不是有效图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_PIXELS) {
                    throw new IllegalArgumentException("图片尺寸不合法");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件内容不是有效图片", exception);
        }
    }

    private List<String> parseImagePaths(String imageList) {
        if (imageList == null || imageList.isBlank()) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (String value : imageList.split(",")) {
            if (!value.isBlank()) {
                paths.add(normalizePublicPath(value));
            }
        }
        return new ArrayList<>(paths);
    }

    private String normalizePublicPath(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("错误的文件名称");
        }
        String normalized = filename.trim().replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (!normalized.startsWith("/blogs/")) {
            throw new IllegalArgumentException("错误的文件名称");
        }
        Path target = resolvePublicPath(normalized);
        if (Files.isDirectory(target)) {
            throw new IllegalArgumentException("错误的文件名称");
        }
        return "/blogs/" + uploadRoot.relativize(target).toString().replace('\\', '/');
    }

    private Path resolvePublicPath(String publicPath) {
        return resolveRelative(publicPath.substring("/blogs/".length()));
    }

    private Path resolveRelative(String relativeName) {
        Path relative = Paths.get(relativeName);
        Path normalizedRelative = relative.normalize();
        if (relative.isAbsolute() || !relative.equals(normalizedRelative)
                || normalizedRelative.startsWith("..")) {
            throw new IllegalArgumentException("非法文件路径");
        }
        Path target = uploadRoot.resolve(normalizedRelative).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        return target;
    }

    private String createRelativeName(String suffix) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        return (hash & 0xF) + "/" + ((hash >> 4) & 0xF) + "/" + name + "." + suffix;
    }

    private String suffixOf(String filename) {
        int index = filename == null ? -1 : filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
