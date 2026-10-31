package io.github.fulingsnow.youshuiauth.listener;

import io.github.fulingsnow.youshuiauth.YouShuiAuth;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.premium.PremiumResolverService;
import org.slf4j.Logger;

/**
 * Factory for creating listener handlers.
 * Single public entry point — handlers themselves are package-private.
 */
public final class ListenerFactory {

    private ListenerFactory() {

    }

    public static PreLoginHandler createPreLoginHandler(AuthCache authCache,
                                                        PremiumResolverService premiumResolverService,
                                                        Settings settings,
                                                        DatabaseManager databaseManager,
                                                        Messages messages,
                                                        Logger logger) {
        return new PreLoginHandler(authCache, premiumResolverService, settings,
                databaseManager, messages, logger);
    }

    public static PostLoginHandler createPostLoginHandler(AuthCache authCache,
                                                          DatabaseManager databaseManager,
                                                          Messages messages,
                                                          Logger logger) {
        return new PostLoginHandler(authCache, databaseManager, messages, logger);
    }

    public static EarlyLoginBlocker createEarlyLoginBlocker(YouShuiAuth plugin) {
        return new EarlyLoginBlocker(plugin);
    }
}
