package com.cityseek.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.Result;
import com.cityseek.dto.UserDTO;
import com.cityseek.entity.BlogComments;
import com.cityseek.entity.User;
import com.cityseek.mapper.BlogCommentsMapper;
import com.cityseek.service.IBlogCommentsService;
import com.cityseek.service.IBlogService;
import com.cityseek.service.IUserService;
import com.cityseek.utils.SystemConstants;
import com.cityseek.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Override
    public Result queryByBlogId(Long blogId, Integer current) {
        if (blogId == null || current == null || current < 1) {
            return Result.fail("请求参数不合法");
        }
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .eq("status", 0)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        if (page.getRecords().isEmpty()) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }
        Map<Long, User> users = userService.listByIds(page.getRecords().stream()
                        .map(BlogComments::getUserId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        page.getRecords().forEach(comment -> {
            User user = users.get(comment.getUserId());
            if (user != null) {
                comment.setName(user.getNickName());
                comment.setIcon(user.getIcon());
            }
        });
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional
    public Result addComment(BlogComments comment) {
        UserDTO currentUser = UserHolder.getUser();
        if (comment == null || comment.getBlogId() == null
                || comment.getContent() == null || comment.getContent().isBlank()) {
            return Result.fail("评论内容不能为空");
        }
        if (blogService.getById(comment.getBlogId()) == null) {
            return Result.fail("笔记不存在");
        }
        comment.setId(null);
        comment.setUserId(currentUser.getId());
        comment.setParentId(comment.getParentId() == null ? 0L : comment.getParentId());
        comment.setAnswerId(comment.getAnswerId() == null ? 0L : comment.getAnswerId());
        comment.setLiked(0);
        comment.setStatus(0);
        if (!save(comment)) {
            throw new IllegalStateException("评论保存失败");
        }
        blogService.update().setSql("comments = COALESCE(comments, 0) + 1")
                .eq("id", comment.getBlogId()).update();
        return Result.ok(comment.getId());
    }
}
