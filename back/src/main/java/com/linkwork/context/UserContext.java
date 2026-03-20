package com.linkwork.context;

/**
 * 用户上下文（ThreadLocal）
 * <p>
 * 替代现有的 X-User-Id Header 和 Mock 硬编码。
 * 由 JwtAuthFilter 在请求进入时设置，请求结束时清除。
 * 用户信息全部来自 JWT payload，不查数据库。
 */
public final class UserContext {

    private static final ThreadLocal<UserInfo> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 设置当前用户（由 Filter 调用）
     */
    public static void set(UserInfo userInfo) {
        HOLDER.set(userInfo);
    }

    /**
     * 获取当前用户（完整信息）
     */
    public static UserInfo get() {
        return HOLDER.get();
    }

    /**
     * 获取当前用户 ID
     */
    public static String getCurrentUserId() {
        UserInfo info = HOLDER.get();
        return info != null ? info.getUserId() : null;
    }

    /**
     * 获取当前用户姓名
     */
    public static String getCurrentUserName() {
        UserInfo info = HOLDER.get();
        return info != null ? info.getName() : null;
    }

    /**
     * 获取当前用户邮箱
     */
    public static String getCurrentEmail() {
        UserInfo info = HOLDER.get();
        return info != null ? info.getEmail() : null;
    }

    /**
     * 清除当前用户（由 Filter 在 finally 中调用，防止内存泄漏）
     */
    public static void clear() {
        HOLDER.remove();
    }
}
