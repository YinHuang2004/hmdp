package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //手机号校验正确，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //将验证码保存到session中
        session.setAttribute("code",code);
        //将手机号也存储到session中
        session.setAttribute("phone",phone);
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
        //校验code
        String code = (String)session.getAttribute("code");
        if(code==null){
            return Result.fail("验证码过期");
        }
        //校验phone
        String phone = (String)session.getAttribute("phone");
        if(!code.equals(loginForm.getCode())||!phone.equals(loginForm.getPhone())){
            return Result.fail("手机号或验证码错误");
        }
        User user = query().eq("phone", phone).one();
        if(user==null){
            //注册用户
            user =  createUserWithPhone(phone);
        }
        UserDTO userDTO=new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        //7.保存用户信息到session中
        session.setAttribute("user",userDTO);
        //登录凭证并不需要，因为当你登录tomcat时，session基于cookie，sessionid自动放在cookie
        return Result.ok();
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
