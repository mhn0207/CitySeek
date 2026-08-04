package com.cityseek.controller;

import com.cityseek.dto.Result;
import com.cityseek.service.UploadFileService;
import com.cityseek.utils.UserHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/upload")
public class UploadController {

    private final UploadFileService uploadFileService;

    public UploadController(UploadFileService uploadFileService) {
        this.uploadFileService = uploadFileService;
    }

    @PostMapping("/blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        String path = uploadFileService.uploadBlogImage(image, UserHolder.getUser().getId());
        return Result.ok(path);
    }

    @DeleteMapping("/blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        uploadFileService.deleteBlogImage(filename, UserHolder.getUser());
        return Result.ok();
    }
}
