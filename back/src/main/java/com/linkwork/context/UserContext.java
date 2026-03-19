package com.linkwork.context;

public final class UserContext {

    private static final ThreadLocal<UserInfo> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    public static void set(UserInfo userInfo) {
        HOLDER.set(userInfo);
    }

    public static void set(String userId, String userName) {
        HOLDER.set(UserInfo.builder().userId(userId).name(userName).build());
    }

    public static UserInfo get() {
        return HOLDER.get();
    }

    public static String getCurrentUserId() {
        UserInfo info = HOLDER.get();
        return info != null ? info.getUserId() : null;
    }

    public static String getCurrentUserName() {
        UserInfo info = HOLDER.get();
        return info != null ? info.getName() : null;
    }

    public static String getCurrentEmail() {
        UserInfo info = HOLDER.get();
        return info != null ? info.getEmail() : null;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
