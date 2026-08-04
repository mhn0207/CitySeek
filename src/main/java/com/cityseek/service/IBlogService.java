package com.cityseek.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cityseek.dto.Result;
import com.cityseek.dto.request.BlogCreateRequest;
import com.cityseek.entity.Blog;

public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result saveBlog(BlogCreateRequest request);

    Result queryBlogOfFollow(Long max, Integer offset);
}
