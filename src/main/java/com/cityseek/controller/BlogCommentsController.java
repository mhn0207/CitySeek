package com.cityseek.controller;

import com.cityseek.dto.Result;
import com.cityseek.entity.BlogComments;
import com.cityseek.service.IBlogCommentsService;
import com.cityseek.utils.PublicEndpoint;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService commentsService;

    @GetMapping("/of/blog")
    @PublicEndpoint
    public Result queryByBlogId(
            @RequestParam("blogId") Long blogId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return commentsService.queryByBlogId(blogId, current);
    }

    @PostMapping
    public Result addComment(@RequestBody BlogComments comment) {
        return commentsService.addComment(comment);
    }
}
