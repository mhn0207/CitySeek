package com.cityseek.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cityseek.dto.Result;
import com.cityseek.dto.request.BlogCommentCreateRequest;
import com.cityseek.entity.BlogComments;

public interface IBlogCommentsService extends IService<BlogComments> {

    Result queryByBlogId(Long blogId, Integer current);

    Result addComment(BlogCommentCreateRequest request);
}
