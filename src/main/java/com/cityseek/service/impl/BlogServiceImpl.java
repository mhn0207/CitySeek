package com.cityseek.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.Result;
import com.cityseek.dto.ScrollResult;
import com.cityseek.dto.UserDTO;
import com.cityseek.entity.Blog;
import com.cityseek.entity.Follow;
import com.cityseek.entity.User;
import com.cityseek.mapper.BlogMapper;
import com.cityseek.service.IBlogService;
import com.cityseek.service.IFollowService;
import com.cityseek.service.IUserService;
import com.cityseek.utils.SystemConstants;
import com.cityseek.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.cityseek.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.cityseek.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int FEED_PAGE_SIZE = 2;
    private static final int MAX_FEED_OFFSET = 10_000;

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        int pageNumber = current == null || current < 1 ? 1 : current;
        Page<Blog> page = query().orderByDesc("liked")
                .page(new Page<>(pageNumber, SystemConstants.MAX_PAGE_SIZE));
        page.getRecords().forEach(this::enrichBlog);
        return Result.ok(page.getRecords());
    }

    @Override
    public Result queryBlogById(Long id) {
        if (id == null) {
            return Result.fail("笔记编号不能为空");
        }
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        enrichBlog(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        if (id == null || getById(id) == null) {
            return Result.fail("笔记不存在");
        }
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            boolean updated = update().setSql("liked = COALESCE(liked, 0) + 1").eq("id", id).update();
            if (updated) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            boolean updated = update().setSql("liked = GREATEST(COALESCE(liked, 0) - 1, 0)")
                    .eq("id", id).update();
            if (updated) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        if (id == null) {
            return Result.fail("笔记编号不能为空");
        }
        Set<String> topFive = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (topFive == null || topFive.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = topFive.stream().map(Long::valueOf).toList();
        String idList = StrUtil.join(",", ids);
        List<UserDTO> users = userService.query().in("id", ids)
                .last("ORDER BY FIELD(id," + idList + ")").list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).toList();
        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        if (blog == null || blog.getShopId() == null
                || StrUtil.isBlank(blog.getTitle()) || StrUtil.isBlank(blog.getContent())) {
            return Result.fail("商铺、标题和内容不能为空");
        }
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        if (!save(blog)) {
            return Result.fail("新增笔记失败");
        }
        List<Follow> followers = followService.query().eq("follow_user_id", user.getId()).list();
        long now = System.currentTimeMillis();
        for (Follow follower : followers) {
            stringRedisTemplate.opsForZSet().add(
                    FEED_KEY + follower.getUserId(), blog.getId().toString(), now);
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        if (max == null || max <= 0 || offset == null || offset < 0 || offset > MAX_FEED_OFFSET) {
            return Result.fail("滚动分页参数错误");
        }
        Long userId = UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(FEED_KEY + userId, 0, max, offset, FEED_PAGE_SIZE);
        if (tuples == null || tuples.isEmpty()) {
            ScrollResult empty = new ScrollResult();
            empty.setList(Collections.emptyList());
            empty.setMinTime(max);
            empty.setOffset(0);
            return Result.ok(empty);
        }

        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = max;
        int recordsAtMinTime = 0;
        boolean cursorFound = false;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            double score = tuple.getScore();
            if (!Double.isFinite(score) || score < 0) {
                continue;
            }
            long time = (long) score;
            if (!cursorFound || time < minTime) {
                minTime = time;
                recordsAtMinTime = 1;
                cursorFound = true;
            } else if (time == minTime) {
                recordsAtMinTime++;
            }
            try {
                ids.add(Long.valueOf(tuple.getValue()));
            } catch (NumberFormatException exception) {
                log.warn("Ignoring invalid blog id in feed: " + tuple.getValue());
            }
        }

        List<Blog> blogs;
        if (ids.isEmpty()) {
            blogs = Collections.emptyList();
        } else {
            String idList = StrUtil.join(",", ids);
            blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idList + ")").list();
            blogs.forEach(this::enrichBlog);
        }
        int nextOffset = 0;
        if (cursorFound) {
            nextOffset = recordsAtMinTime;
            if (minTime == max) {
                nextOffset += offset;
            }
        }
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(cursorFound ? minTime : max);
        result.setOffset(nextOffset);
        return Result.ok(result);
    }

    private void enrichBlog(Blog blog) {
        queryBlogUser(blog);
        isBlogLiked(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            blog.setIsLike(false);
            return;
        }
        Double score = stringRedisTemplate.opsForZSet()
                .score(BLOG_LIKED_KEY + blog.getId(), currentUser.getId().toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }
}
