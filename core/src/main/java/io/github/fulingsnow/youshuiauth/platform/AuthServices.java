package io.github.fulingsnow.youshuiauth.platform;

import io.github.fulingsnow.youshuiauth.auth.totp.PendingTotpStore;
import io.github.fulingsnow.youshuiauth.auth.totp.TotpReplayGuard;
import io.github.fulingsnow.youshuiauth.auth.totp.TotpService;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;

public interface AuthServices {

    DatabaseManager databaseManager();

    AuthCache authCache();

    Settings settings();

    Messages messages();

    org.slf4j.Logger logger();

    AuditLogService auditLogService();

    TotpService totpService();

    PendingTotpStore pendingTotpStore();

    TotpReplayGuard totpReplayGuard();

    PlatformAccess platformAccess();

    ConnectionOps connections();

    TimeoutOps timeouts();

    TransferOps transfers();

    ReportOps reports();

    QqOps qq();

    boolean reloadConfig();
}
