package io.github.fulingsnow.youshuiauth.platform;

public interface ConnectionOps {

    ConnectionRef capture(AuthPlayer player);

    /**
     * 新连接开始：单实例平台据此推进代际，使上一次连接捕获的引用失效。
     * 代理端有真实的连接生命周期对象，保持默认空实现。
     */
    default void beginSession(AuthPlayer player) {
        /* no-op */
    }

    boolean isCurrent(ConnectionRef operation);

    boolean runIfCurrent(ConnectionRef operation, Runnable effect);

    boolean retire(AuthPlayer player);
}
