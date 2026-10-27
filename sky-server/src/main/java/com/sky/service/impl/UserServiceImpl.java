package com.sky.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.constant.MessageConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.LoginFailedException;
import com.sky.mapper.UserMapper;
import com.sky.properties.WeChatProperties;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    //微信服务接口地址
    public static final String WX_LOGIN = "https://api.weixin.qq.com/sns/jscode2session";

    @Autowired
    private WeChatProperties weChatProperties;
    @Autowired
    private UserMapper userMapper;

    /**
     * 微信登录
     * @param userLoginDTO
     * @return
     */
    public User wxLogin(UserLoginDTO userLoginDTO) {
        String code = userLoginDTO == null ? null : userLoginDTO.getCode();
        if (!StringUtils.hasText(code)) {
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }

        String openid = getOpenid(code);

        //判断openid是否为空，如果为空表示登录失败，抛出业务异常
        if (openid == null) {
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }

        //判断当前用户是否为新用户
        User user = userMapper.getByOpenid(openid);

        //如果是新用户，自动完成注册
        if(user == null){
            user = User.builder()
                    .openid(openid)
                    .createTime(LocalDateTime.now())
                    .build();
            userMapper.insert(user);
        }

        //返回这个用户对象
        return user;
    }

    /**
     * 调用微信接口服务，获取微信用户的openid
     * @param code
     * @return
     */
    private String getOpenid(String code){
        //调用微信接口服务，获得当前微信用户的openid
        Map<String, String> map = new HashMap<>();
        map.put("appid",weChatProperties.getAppid());
        map.put("secret",weChatProperties.getSecret());
        map.put("js_code",code);
        map.put("grant_type","authorization_code");

        String json;
        try {
            json = HttpClientUtil.doGet(WX_LOGIN, map);
        } catch (Exception e) {
            // 微信服务不可用与"登录失败"要区分开，否则运维只能看到一个笼统的登录失败
            log.error("调用微信 jscode2session 失败", e);
            throw new LoginFailedException("登录服务暂时不可用，请稍后重试");
        }

        JsonNode response;
        try {
            response = JsonUtils.readTree(json);
        } catch (IllegalArgumentException e) {
            log.error("微信登录响应不是合法 JSON，响应={}", json);
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }

        String openid = response.path("openid").asText(null);
        if (openid == null) {
            // errcode/errmsg 不含敏感凭证，可以记；secret 已经在请求参数里，不要落日志
            log.warn("微信登录未返回 openid，errcode={}，errmsg={}",
                    response.path("errcode").asText(), response.path("errmsg").asText());
        }
        return openid;
    }
}
