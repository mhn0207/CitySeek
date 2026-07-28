package com.cityseek.service;

import com.cityseek.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;
import com.cityseek.dto.Result;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {


    Result queryByBlogId(Long blogId, Integer current);

    Result addComment(BlogComments comment);
}
