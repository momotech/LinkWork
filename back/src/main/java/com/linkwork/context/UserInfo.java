package com.linkwork.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 当前登录用户信息（从 JWT payload 解析，不查数据库）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {

    /** 唯一用户标识 */
    private String userId;

    /** 姓名 */
    private String name;

    /** 邮箱 */
    private String email;

    /** 工号 */
    private String workId;

    /** 头像 URL */
    private String avatarUrl;

    /** 权限列表 */
    private List<String> permissions;
}
