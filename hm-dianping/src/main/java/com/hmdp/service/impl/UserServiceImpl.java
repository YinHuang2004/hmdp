package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //手机号校验正确，生成验证码
        String code = RandomUtil.randomNumbers(6);
       //保存验证码到redis
       stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //模拟发送验证码
        log.info("验证码发送成功:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //首先校验手机号，因为可能用户在期间修改手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误");
        }
        String phone=loginForm.getPhone();
        //从redis获取校验code
        String code =stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY +phone);
        if(code==null){
            return Result.fail("验证码过期");
        }
     //比较redis中的code和用户登录的code是否相等
        if(!code.equals(loginForm.getCode())){
            return Result.fail("验证码错误");
        }
        //如果验证码和手机号一致就查询用户是否存在
        User user = query().eq("phone", phone).one();
        if(user==null){
            //注册用户
            user =  createUserWithPhone(phone);
        }
        //7.保存用户信息到redis中
        //7.1生成随机token作为登录令牌
        String token = RedisConstants.LOGIN_USER_KEY+UUID.randomUUID().toString(true);
        //7.2将user对象转成hash存储在redis

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //存储到redis中
        //首先需要将user对象转为map对象(也可以一个一个插入手动序列化其他类型为string）
        // 将对象中字段全部转成string类型，StringRedisTemplate只能存字符串类型的数据
        //将对象的字段全部转成string类型，比如id->String,stringRedisTemplate要求所有的keyvalue都是string类型
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        //字段值修改器允许去修改字段值类型（其他类型->string),拿到字段名和字段值，我们只修改字段值类型为string
                                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        stringRedisTemplate.opsForHash().putAll(token,userMap);
        //设置token有效期
        stringRedisTemplate.expire(token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //7.3返回token给前端

        //登录凭证并不需要，因为当你登录tomcat时，session基于cookie，sessionid自动放在cookie
        // 将token返回给前端，前端后续请求会在header中携带authorization=token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        //随机昵称
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        //mp的方法
        save(user);
        return user;
    }
}
