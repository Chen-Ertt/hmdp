package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queyHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询BLog所属的用户，并判断当前用户是否能够点赞
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询blog相关的用户信息
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogByID(Integer id) {
        Blog blog = getById(id);
        if(blog == null) {
            return Result.fail("blog不存在");
        }
        queryBlogUser(blog);

        // 判断当前blog是否被点赞过
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            return;
        }
        Long userID = user.getId();
        // 2.判断是否点赞
        String key = "blog:like:" + blog.getId().toString();
        Double score = stringRedisTemplate.opsForZSet().score(key, userID.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 根据user是否点赞，进行blog点赞数的update
     * 1. 使用set
     * 2. 使用zset，能够实现点赞排行榜
     * 使用点赞时间戳作为score
     * 使用score获取对应元素的分数，若为空说明元素不存在
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userID = UserHolder.getUser().getId();
        // 2.判断是否点赞
        String key = "blog:like:" + id.toString();
        Double score = stringRedisTemplate.opsForZSet().score(key, userID.toString());
        if(score != null) {
            // 点过
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userID.toString());
            }
        } else {
            // 没点
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userID.toString(), System.currentTimeMillis());
            }
        }

        return Result.ok();
    }

    /**
     * 获取点赞排行
     * 使用了stream和lambda（待学习）
     */
    @Override
    public Result queryBlogLikes(Integer id) {
        // 获取到top5的用户
        String key = "blog:like:" + id.toString();
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect((Collectors.toList()));

        return Result.ok(users);
    }

    /**
     * 当发布笔记时，同步将笔记推送到粉丝的收件箱（使用zset实现）
     * 推送内容为blog的id和时间戳（score）
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(userId);
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(isSuccess) {
            // 查询粉丝list select * from tb_follow where follow_user_id = userId
            List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
            // 遍历所有fans并推送blog id和时间戳
            long timeScore = System.currentTimeMillis();
            for(Follow follow : follows) {
                Long fansId = follow.getId();
                String key = "feed:" + fansId;
                stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), timeScore);
            }

        }


        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户并查询收件箱
        Long userId = UserHolder.getUser().getId();
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if(tuples == null || tuples.isEmpty()) {
            return Result.ok();
        }

        // 2.解析收件箱内容
        // 获取了最小时间戳和最小时间戳的重复数量
        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> tuple : tuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if(time == minTime) {
                os++;
            } else {
                os = 1;
                minTime = time;
            }
        }

        // 3.获取Blog封装返回
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        // 需要查询blog相关的用户和点赞
        for(Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        scrollResult.setList(blogs);
        return Result.ok(scrollResult);
    }
}
