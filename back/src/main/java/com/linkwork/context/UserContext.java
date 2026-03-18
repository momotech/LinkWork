package com.linkwork.context;

/**
 * Thread-local holder for the current authenticated user.
 * Populated by auth filter/interceptor before controller methods execute.
 */
public final class UserContext {

    private static final ThreadLocal<UserInfo> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    public static void set(String userId, String userName) {
        HOLDER.set(new UserInfo(userId, userName));
    }

    public static String getCurrentUserId() {
        UserInfo info = HOLDER.get();
        return info == null ? null : info.userId;
    }

    public static String getCurrentUserName() {
        UserInfo info = HOLDER.get();
        return info == null ? null : info.userName;
    }

    public static void clear() {
        HOLDER.remove();
    }

    private record UserInfo(String userId, String userName) {}
}
