package io.github.fulingsnow.youshuiauth.platform;

import java.time.Duration;
import java.util.function.Consumer;

public interface PlatformScheduler {

    TaskHandle scheduleOnce(Runnable task, Duration delay);

    TaskHandle scheduleRepeating(Runnable task, Duration delay, Duration period);

    TaskHandle scheduleForPlayer(AuthPlayer player, Consumer<TaskHandle> assign, Runnable task,
                                 Duration delay);

    interface TaskHandle {

        void cancel();

        boolean isCancelled();
    }
}
