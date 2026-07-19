package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if(!(handler instanceof HandlerMethod)){
            //当前请求是静态请求，放行
            return true;
        }
        //获取session
        HttpSession session = request.getSession();
        //获取session中的user（与登录时 setAttribute("user", ...) 的 key 保持一致）
        Object userDto = session.getAttribute("user");
        if(userDto==null){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);//状态码为401
            return false;
        }
        //保存用户信息到threadlocal
        UserHolder.saveUser((UserDTO) userDto);
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
