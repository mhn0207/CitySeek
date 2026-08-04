package com.cityseek.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cityseek.dto.Result;
import com.cityseek.dto.UserDTO;
import com.cityseek.dto.request.BlogCreateRequest;
import com.cityseek.entity.Blog;
import com.cityseek.service.IBlogService;
import com.cityseek.utils.PublicEndpoint;
import com.cityseek.utils.SystemConstants;
import com.cityseek.utils.UserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/blog")
public class BlogController {

    private final IBlogService blogService;

    public BlogController(IBlogService blogService) {
        this.blogService = blogService;
    }

    @PostMapping
    public Result saveBlog(@Valid @RequestBody BlogCreateRequest request) {
        return blogService.saveBlog(request);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = blogService.query().eq("user_id", user.getId())
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    @PublicEndpoint
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    @GetMapping("/{id}")
    @PublicEndpoint
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    @PublicEndpoint
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    @PublicEndpoint
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        Page<Blog> page = blogService.query().eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
