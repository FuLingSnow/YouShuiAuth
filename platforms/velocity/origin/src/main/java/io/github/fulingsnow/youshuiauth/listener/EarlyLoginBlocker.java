package io.github.fulingsnow.youshuiauth.listener;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.YouShuiAuth;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Startup queue for connections arriving before YouShuiAuth finishes initialization.
 * Instead of kicking players, holds their PreLogin event until the plugin is ready,
 * then runs the full {@link AuthListener#onPreLogin} pipeline on the queued event.
 * <p>
 * The delegation is what makes the queue safe. Velocity snapshots the handler list
 * when an event is fired, so {@code AuthListener} — registered in phase 8 of async
 * initialization — is invisible to any event queued here, no matter its priority.
 * Merely releasing the event would let it complete with no premium check and no
 * {@code forceOnlineMode()}, handing a premium nickname an offline UUID on a proxy
 * with {@code online-mode = false}. This blocker is therefore the <em>only</em>
 * YouShuiAuth handler a queued event will ever see, and must invoke the auth pipeline
 * itself before resuming.
 * <p>
 * Failure paths deny by setting the event result before the continuation resumes:
 * Velocity treats {@code resumeWithException} as log-and-continue, so an exception
 * alone would release the connection with the default (allowed) result.
 */
public class EarlyLoginBlocker {

    private final YouShuiAuth plugin;
    private final Logger logger;

    EarlyLoginBlocker(YouShuiAuth plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    @Subscribe(priority = 100)
    public EventTask onPreLogin(PreLoginEvent event) {
        if (plugin.isInitialized()) {

            return null;
        }

        String username = event.getUsername();
        logger.info("启动队列：玩家 {} 正在等待 YouShuiAuth 初始化...", username);

        return EventTask.resumeWhenComplete(
                plugin.getInitializationFuture()
                        .thenCompose(ignored -> delegateToAuthPipeline(event, username))
                        .exceptionally(throwable -> {
                            logger.warn("启动队列：初始化失败或认证委托出错（{}）——拒绝连接",
                                    username, throwable);

                            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                                    Component.text("YouShuiAuth ⏳",
                                            NamedTextColor.RED)));
                            return null;
                        })
        );
    }

    /**
     * Runs the queued event through the real auth pipeline and completes when the
     * pipeline does. {@code AuthListener.onPreLogin} is designed for Netty threads:
     * fast synchronous validation, then an {@link EventTask} whose I/O already hops
     * to the virtual-thread executor — so invoking it from the initialization
     * future's completion thread blocks nothing.
     */
    private CompletableFuture<Void> delegateToAuthPipeline(PreLoginEvent event, String username) {
        AuthListener authListener = plugin.getAuthListener();
        if (authListener == null) {

            throw new IllegalStateException(
                    "AuthListener unavailable after initialization for " + username);
        }

        logger.info("启动队列：YouShuiAuth 已初始化，对 {} 执行认证检查", username);
        EventTask delegated = authListener.onPreLogin(event);
        if (delegated == null) {

            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        delegated.execute(new Continuation() {
            @Override
            public void resume() {
                completion.complete(null);
            }

            @Override
            public void resumeWithException(Throwable exception) {
                completion.completeExceptionally(exception);
            }
        });
        return completion;
    }
}
