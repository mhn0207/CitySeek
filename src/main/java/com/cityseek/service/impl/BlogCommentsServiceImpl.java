package com.cityseek.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.Result;
import com.cityseek.dto.UserDTO;
import com.cityseek.dto.request.BlogCommentCreateRequest;
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
    public Result addComment(BlogCommentCreateRequest request) {
        UserDTO currentUser = UserHolder.getUser();
        if (blogService.getById(request.getBlogId()) == null) {
            return Result.fail("笔记不存在");
        }
        BlogComments comment = new BlogComments()
                .setUserId(currentUser.getId())
                .setBlogId(request.getBlogId())
                .setParentId(request.getParentId() == null ? 0L : request.getParentId())
                .setAnswerId(request.getAnswerId() == null ? 0L : request.getAnswerId())
                .setContent(request.getContent().trim())
                .setLiked(0)
                .setStatus(0);
        if (!save(comment)) {
            throw new IllegalStateException("评论保存失败");
        }
        blogService.update().setSql("comments = COALESCE(comments, 0) + 1")
                .eq("id", comment.getBlogId()).update();
        return Result.ok(comment.getId());
    }
}