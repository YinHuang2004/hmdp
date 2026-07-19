package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;
@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.info("刷新token拦截器的调用");
        if(!(handler instanceof HandlerMethod)){
            //当前请求是静态请求，放行
            return true;
        }
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        //2.基于token获取redis中的用户
        if(StringUtils.isEmpty(token)){
            return true;
        }
        //基于token获取redis中的用户
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(token);
        if(entries==null||entries.isEmpty()){
            return true;
        }

        //将查询到的hash数据转为userdto对象保存到threadlocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);


        //保存用户信息到threadlocal
        UserHolder.saveUser(userDTO);
        //刷新token有效期，只要该用户还在发起请求那么就刷新token的过期时间
        stringRedisTemplate.expire(token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        log.info("拦截器的销毁");
        UserHolder.removeUser();
    }
}
