package io.github.fulingsnow.youshuiauth.platform;

import net.kyori.adventure.text.Component;

import java.util.Locale;
import java.util.Optional;

public interface AuthSender {

    String name();

    boolean hasPermission(String node);

    boolean sendMessage(Component message);

    Optional<AuthPlayer> asPlayer();

    Locale effectiveLocale();
}
