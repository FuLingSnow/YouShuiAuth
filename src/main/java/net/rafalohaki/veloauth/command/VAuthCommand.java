package net.rafalohaki.veloauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rafalohaki.veloauth.audit.AuditEventType;
import net.rafalohaki.veloauth.audit.AuditLogService;
import net.rafalohaki.veloauth.config.Settings;
import net.rafalohaki.veloauth.model.RegisteredPlayer;
import net.rafalohaki.veloauth.util.PlayerAddressUtils;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.rafalohaki.veloauth.database.DatabaseManager;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Komenda /vauth - komendy administratora.
 * Extracted from CommandHandler for single-responsibility.
 */
class VAuthCommand implements SimpleCommand {

    private static final String ERROR_DATABASE_QUERY = "error.database.query";
    private static final String ERROR_REPORT_FAILED = "admin.report.failed";
    private static final String CONFLICT_PREFIX = "   §7";
    private static final String RELOAD_WARNING_KEY = "admin.reload.pending_restart";
    private static final Marker DB_MARKER = MarkerFactory.getMarker("DATABASE");
    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");

    private static final String SUBCOMMAND_QQ = "qq";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of(
            "reload", "cache-reset", "stats", "conflicts", "2fa-remove", SUBCOMMAND_QQ, "report");
    private static final List<String> QQ_SUBCOMMANDS = List.of("list", "unbind", "reset");
    private static final List<String> PLAYER_ARG_SUBCOMMANDS = List.of("2fa-remove", "cache-reset");

    private final CommandContext ctx;

    VAuthCommand(CommandContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandHelper.runAsAdmin(invocation, ctx.messages(), this::dispatchSubcommand);
    }

    private void dispatchSubcommand(CommandSource source, String[] args) {
        if (args.length == 0) {
            sendAdminHelp(source);
            return;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "reload" -> handleReloadCommand(source);
            case "cache-reset" -> handleCacheResetCommand(source, args);
            case "stats" -> handleStatsCommand(source);
            case "conflicts" -> handleConflictsCommand(source);
            case "2fa-remove" -> handleTwoFactorRemoveCommand(source, args);
            case "qq" -> handleQqCommand(source, args);
            case "report" -> handleReportCommand(source);
            default -> {
                source.sendMessage(ctx.messages().component(
                        "admin.unknown_command", NamedTextColor.RED, subcommand));
                sendAdminHelp(source);
            }
        }
    }

    private void handleQqCommand(CommandSource source, String[] args) {
        QqAuthService service = ctx.plugin().getQqAuthService();
        if (service == null || !service.isEnabled()) {
            source.sendMessage(ctx.messages().component("admin.qq.disabled", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            CommandHelper.sendError(source, ctx.messages(), "admin.qq.usage");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "list" -> ctx.runAsyncCommand(source, () -> processQqList(source), ERROR_DATABASE_QUERY);
            case "unbind" -> {
                if (args.length != 3) {
                    CommandHelper.sendError(source, ctx.messages(), "admin.qq.usage");
                    return;
                }
                ctx.runAsyncCommand(source,
                        () -> processQqUnbind(source, args[2]), ERROR_DATABASE_QUERY);
            }
            case "reset" -> {
                if (args.length != 3) {
                    CommandHelper.sendError(source, ctx.messages(), "admin.qq.usage");
                    return;
                }
                ctx.runAsyncCommand(source,
                        () -> processQqReset(source, args[2]), ERROR_DATABASE_QUERY);
            }
            default -> CommandHelper.sendError(source, ctx.messages(), "admin.qq.usage");
        }
    }

    private void processQqList(CommandSource source) {
        if (!ctx.ensureDatabaseConnected(source, "Admin qq list")) {
            return;
        }
        try {
            List<net.rafalohaki.veloauth.model.QqBinding> bindings =
                    ctx.databaseManager().getQqDao().listBindings(100);
            if (bindings.isEmpty()) {
                source.sendMessage(ctx.messages().component(
                        "admin.qq.list.empty", NamedTextColor.YELLOW));
                return;
            }
            source.sendMessage(ctx.messages().component(
                    "admin.qq.list.header", NamedTextColor.GOLD, bindings.size()));
            for (var binding : bindings) {
                source.sendMessage(ctx.messages().component(
                        "admin.qq.list.entry", NamedTextColor.GRAY,
                        maskQq(binding.getQqId()),
                        binding.getNicknameAtBind() == null ? "?" : binding.getNicknameAtBind(),
                        binding.getUuid()));
            }
        } catch (SQLException e) {
            ctx.logger().error(DB_MARKER, "Admin qq list failed", e);
            ctx.sendDatabaseErrorMessage(source);
        }
    }

    private void processQqUnbind(CommandSource source, String target) {
        if (!ctx.ensureDatabaseConnected(source, "Admin qq unbind")) {
            return;
        }
        try {
            String uuid = null;
            boolean deleted;
            if (isQqNumber(target)) {
                deleted = ctx.databaseManager().getQqDao()
                        .deleteByQq(QqAuthService.PLATFORM_QQ, target);
            } else {
                var dbResult = ctx.databaseManager().findPlayerByNickname(target).join();
                if (ctx.handleDatabaseError(dbResult, source, target, "Admin qq unbind lookup")) {
                    return;
                }
                RegisteredPlayer player = dbResult.getValue();
                if (player == null) {
                    source.sendMessage(ctx.messages().component(
                            "admin.qq.unbind.not_found", NamedTextColor.YELLOW, target));
                    return;
                }
                uuid = player.getUuid();
                deleted = ctx.databaseManager().getQqDao()
                        .deleteByUuid(QqAuthService.PLATFORM_QQ, uuid);
            }

            if (!deleted) {
                source.sendMessage(ctx.messages().component(
                        "admin.qq.unbind.not_found", NamedTextColor.YELLOW, target));
                return;
            }
            if (uuid != null) {
                QqAuthService service = ctx.plugin().getQqAuthService();
                if (service != null) {
                    service.revokeRememberTokens(UUID.fromString(uuid));
                }
            }
            AuditLogService audit = ctx.auditLogService();
            if (audit != null) {
                audit.save(AuditEventType.QQ_UNBIND, target, null, "admin");
            }
            source.sendMessage(ctx.messages().component(
                    "admin.qq.unbind.success", NamedTextColor.GREEN, target));
        } catch (SQLException | CompletionException e) {
            ctx.logger().error(DB_MARKER, "Admin qq unbind failed for {}", target, e);
            ctx.sendDatabaseErrorMessage(source);
        }
    }

    /**
     * Recovery for QQ-first accounts: deletes the passwordless AUTH row and the binding so the
     * player can register again. Refuses to touch accounts that have a real password.
     */
    private void processQqReset(CommandSource source, String nickname) {
        if (!ctx.ensureDatabaseConnected(source, "Admin qq reset")) {
            return;
        }
        try {
            var dbResult = ctx.databaseManager().findPlayerByNickname(nickname).join();
            if (ctx.handleDatabaseError(dbResult, source, nickname, "Admin qq reset lookup")) {
                return;
            }
            RegisteredPlayer player = dbResult.getValue();
            if (player == null) {
                source.sendMessage(ctx.messages().component(
                        "admin.qq.reset.not_found", NamedTextColor.YELLOW, nickname));
                return;
            }
            if (!QqAuthService.QQ_ONLY_HASH.equals(player.getHash())) {
                source.sendMessage(ctx.messages().component(
                        "admin.qq.reset.not_qq_only", NamedTextColor.RED, nickname));
                return;
            }
            String uuid = player.getUuid();
            var deleteResult = ctx.databaseManager().deletePlayer(nickname).join();
            if (ctx.handleDatabaseError(deleteResult, source, nickname, "Admin qq reset delete")) {
                return;
            }
            if (!Boolean.TRUE.equals(deleteResult.getValue())) {
                source.sendMessage(ctx.messages().component(
                        "admin.qq.reset.not_found", NamedTextColor.YELLOW, nickname));
                return;
            }
            ctx.databaseManager().getQqDao().deleteByUuid(QqAuthService.PLATFORM_QQ, uuid);
            QqAuthService service = ctx.plugin().getQqAuthService();
            if (service != null) {
                service.revokeRememberTokens(UUID.fromString(uuid));
            }
            AuditLogService audit = ctx.auditLogService();
            if (audit != null) {
                audit.save(AuditEventType.QQ_UNBIND, nickname, null, "admin-reset");
            }
            source.sendMessage(ctx.messages().component(
                    "admin.qq.reset.success", NamedTextColor.GREEN, nickname));
        } catch (SQLException | CompletionException | IllegalArgumentException e) {
            ctx.logger().error(DB_MARKER, "Admin qq reset failed for {}", nickname, e);
            ctx.sendDatabaseErrorMessage(source);
        }
    }

    private boolean isQqNumber(String value) {
        return value != null && value.length() >= 5 && value.chars().allMatch(Character::isDigit);
    }

    private String maskQq(String qqId) {
        if (qqId == null || qqId.length() <= 4) {
            return "****";
        }
        return "****" + qqId.substring(qqId.length() - 4);
    }

    private void handleTwoFactorRemoveCommand(CommandSource source, String[] args) {
        if (args.length != 2) {
            CommandHelper.sendError(source, ctx.messages(), "admin.2fa.remove.usage");
            return;
        }
        String nickname = args[1];
        ctx.runAsyncCommand(source, () -> processTwoFactorRemove(source, nickname), ERROR_DATABASE_QUERY);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void processTwoFactorRemove(CommandSource source, String nickname) {
        if (!ctx.ensureDatabaseConnected(source, "Admin 2fa-remove")) {
            return;
        }

        DatabaseManager.DbResult<RegisteredPlayer> dbResult;
        try {
            dbResult = ctx.databaseManager().findPlayerByNickname(nickname).join();
        } catch (CompletionException e) {
            ctx.logger().error(DB_MARKER, "Admin 2fa-remove lookup failed for {}", nickname, e);
            ctx.sendDatabaseErrorMessage(source);
            return;
        }
        if (ctx.handleDatabaseError(dbResult, source, nickname, "Admin 2fa-remove lookup")) {
            return;
        }

        RegisteredPlayer registered = dbResult.getValue();
        if (registered == null) {
            source.sendMessage(ctx.messages().component(
                    "admin.2fa.remove.not_found", NamedTextColor.RED, nickname));
            return;
        }
        if (registered.getTotpToken() == null || registered.getTotpToken().isBlank()) {
            source.sendMessage(ctx.messages().component(
                    "admin.2fa.remove.not_enabled", NamedTextColor.YELLOW, nickname));
            return;
        }

        registered.setTotpToken(null);
        var saveResult = ctx.databaseManager().savePlayer(registered).join();
        if (ctx.handleDatabaseError(saveResult, source, nickname, "Admin 2fa-remove save")) {
            return;
        }
        if (!Boolean.TRUE.equals(saveResult.getValue())) {
            ctx.sendDatabaseErrorMessage(source);
            return;
        }

        // Disconnect the online player so they re-/login without 2FA (token is gone now).
        // Mirrors /unregister behavior: don't leave the player in a stale session.
        // Also wipe any in-flight pending TOTP verification: if the admin is removing 2FA
        // because of a suspected compromise, leaving a stale `pending LOGIN` state (with
        // the now-old token cached on it) would let an attacker mid-flow complete their
        // verification against the soon-to-be-deleted token. Belt and suspenders.
        ctx.plugin().getServer().getPlayer(nickname).ifPresent(p -> {
            ctx.pendingTotpStore().invalidate(p.getUniqueId());
            p.disconnect(ctx.messages().component("general.kick.message", NamedTextColor.YELLOW));
            if (ctx.logger().isInfoEnabled()) {
                ctx.logger().info(AUTH_MARKER, "Disconnected {} after admin 2FA wipe", nickname);
            }
        });

        emitAdminAudit(source, nickname, registered);

        source.sendMessage(ctx.messages().component(
                "admin.2fa.remove.success", NamedTextColor.GREEN, nickname));
    }

    private void emitAdminAudit(CommandSource source, String nickname, RegisteredPlayer registered) {
        AuditLogService audit = ctx.auditLogService();
        if (audit == null) {
            return;
        }
        String adminName = source instanceof Player p ? p.getUsername() : "CONSOLE";
        String adminIp = source instanceof Player p ? PlayerAddressUtils.getPlayerIp(p) : null;
        String details = "admin=" + adminName + " uuid=" + registered.getUuid();
        audit.save(AuditEventType.TWO_FACTOR_DISABLED, nickname, adminIp, details);
    }

    private void handleReloadCommand(CommandSource source) {
        boolean success = ctx.plugin().reloadConfig();
        if (success) {
            source.sendMessage(ctx.messages().component("admin.reload.success", NamedTextColor.GREEN));
            sendLocalizedReloadWarning(source, ctx.settings().getPendingRestartChanges());
        } else {
            source.sendMessage(ctx.messages().component("admin.reload.failed", NamedTextColor.RED));
        }
    }

    private void handleConflictsCommand(CommandSource source) {
        ctx.runAsyncCommand(source, () -> processConflictsCommand(source), ERROR_DATABASE_QUERY);
    }

    private void handleCacheResetCommand(CommandSource source, String[] args) {
        if (args.length == 2) {
            String nickname = args[1];
            ctx.runAsyncCommand(source, () -> processSinglePlayerCacheReset(source, nickname), ERROR_DATABASE_QUERY);
        } else {
            ctx.authCache().clearAll();
            source.sendMessage(ctx.messages().component("admin.cache_reset.success", NamedTextColor.GREEN));
        }
    }

    private void handleStatsCommand(CommandSource source) {
        ctx.runAsyncCommand(source, () -> processStatsCommand(source), ERROR_DATABASE_QUERY);
    }

    private void handleReportCommand(CommandSource source) {
        Settings.OperationSettings operationSettings = ctx.settings().captureOperationSettings();
        if (!operationSettings.report().enabled()) {
            source.sendMessage(ctx.messages().component("admin.report.disabled", NamedTextColor.RED));
            return;
        }
        source.sendMessage(ctx.messages().component("admin.report.generating", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.report.warning", NamedTextColor.YELLOW));
        ctx.runAsyncCommand(
                source, () -> processReport(source, operationSettings), ERROR_REPORT_FAILED);
    }

    private void processReport(CommandSource source, Settings.OperationSettings operationSettings) {
        net.rafalohaki.veloauth.report.ReportService reportService = ctx.reportService();
        if (reportService == null) {
            source.sendMessage(ctx.messages().component(
                    ERROR_REPORT_FAILED, NamedTextColor.RED, "report service not initialized"));
            return;
        }
        net.rafalohaki.veloauth.report.ReportService.ReportResult result =
                reportService.generateAndUpload(operationSettings);
        if (result.success()) {
            source.sendMessage(ctx.messages().component(
                    "admin.report.success", NamedTextColor.GREEN, result.url()));
        } else {
            source.sendMessage(ctx.messages().component(
                    ERROR_REPORT_FAILED, NamedTextColor.RED, result.error()));
        }
    }

    private void sendAdminHelp(CommandSource source) {
        source.sendMessage(ctx.messages().component("admin.help.header", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.help.reload", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.help.cache", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.help.stats", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.help.conflicts", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.help.qq", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.help.2fa_remove", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.help.report", NamedTextColor.YELLOW));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return matching(TOP_LEVEL_SUBCOMMANDS, args.length == 1 ? args[0] : "");
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (SUBCOMMAND_QQ.equals(subcommand)) {
            if (args.length == 2) {
                return matching(QQ_SUBCOMMANDS, args[1]);
            }
            if (args.length == 3 && isQqTargetSubcommand(args[1])) {
                return matching(onlinePlayerNames(), args[2]);
            }
            return List.of();
        }
        if (args.length == 2 && PLAYER_ARG_SUBCOMMANDS.contains(subcommand)) {
            return matching(onlinePlayerNames(), args[1]);
        }
        return List.of();
    }

    private static boolean isQqTargetSubcommand(String value) {
        return "unbind".equalsIgnoreCase(value) || "reset".equalsIgnoreCase(value);
    }

    private static List<String> matching(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(normalized)).toList();
    }

    private List<String> onlinePlayerNames() {
        return ctx.plugin().getServer().getAllPlayers().stream()
                .map(Player::getUsername)
                .sorted()
                .toList();
    }

    private void processConflictsCommand(CommandSource source) {
        if (!ctx.ensureDatabaseConnected(source, "Admin conflicts command")) {
            return;
        }

        List<RegisteredPlayer> conflicts;
        try {
            conflicts = ctx.databaseManager().findPlayersInConflictMode().join();
        } catch (CompletionException e) {
            ctx.logger().error(DB_MARKER, "Failed to fetch conflict players", e);
            ctx.sendDatabaseErrorMessage(source);
            return;
        }
        if (conflicts == null || !ctx.ensureDatabaseConnected(source, "Admin conflicts command")) {
            return;
        }

        source.sendMessage(ctx.messages().component("admin.conflicts.header", NamedTextColor.YELLOW));

        if (conflicts.isEmpty()) {
            source.sendMessage(ctx.messages().component("admin.conflicts.none", NamedTextColor.GREEN));
            return;
        }

        source.sendMessage(ctx.messages().component(
                "admin.conflicts.found", NamedTextColor.YELLOW, conflicts.size()));

        for (int i = 0; i < conflicts.size(); i++) {
            RegisteredPlayer conflict = conflicts.get(i);
            // Initial capacity sized for a typical conflict block: ~5 fields × ~40 chars + headers.
            StringBuilder conflictInfo = new StringBuilder(256);
            conflictInfo.append("§e").append(i + 1).append(". §f").append(conflict.getNickname()).append("\n");
            conflictInfo.append(CONFLICT_PREFIX).append(ctx.messages().get("admin.conflicts.uuid", conflict.getUuid())).append("\n");
            conflictInfo.append(CONFLICT_PREFIX).append(ctx.messages().get("admin.conflicts.ip", conflict.getIp())).append("\n");

            long conflictTime = conflict.getConflictTimestamp();
            if (conflictTime > 0) {
                long hoursAgo = (System.currentTimeMillis() - conflictTime) / (1000 * 60 * 60);
                conflictInfo.append(CONFLICT_PREFIX).append(ctx.messages().get("admin.conflicts.hours_ago", hoursAgo)).append("\n");
            }

            if (conflict.getOriginalNickname() != null &&
                    !conflict.getOriginalNickname().equals(conflict.getNickname())) {
                conflictInfo.append(CONFLICT_PREFIX)
                        .append(ctx.messages().get("admin.conflicts.original_nick", conflict.getOriginalNickname()))
                        .append("\n");
            }

            boolean isPremium = ctx.databaseManager().isPlayerPremiumRuntime(conflict);
            String statusKey = isPremium ? "admin.conflicts.status_premium" : "admin.conflicts.status_offline";
            conflictInfo.append(CONFLICT_PREFIX).append(ctx.messages().get(statusKey)).append("\n");

            source.sendMessage(ValidationUtils.createWarningComponent(conflictInfo.toString()));
        }

        source.sendMessage(ValidationUtils.createWarningComponent(""));
        source.sendMessage(ctx.messages().component("admin.conflicts.tips_header", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.conflicts.tip_premium", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.conflicts.tip_offline", NamedTextColor.YELLOW));
        source.sendMessage(ctx.messages().component("admin.conflicts.tip_admin", NamedTextColor.YELLOW));
    }

    private void processSinglePlayerCacheReset(CommandSource source, String nickname) {
        PlayerLookupResult playerLookup = resolvePlayerUuid(source, nickname);
        if (playerLookup.handledError()) {
            return;
        }

        UUID playerUuid = playerLookup.playerUuid();
        if (playerUuid == null) {
            source.sendMessage(ctx.messages().component(
                    "admin.cache_reset.player_not_found", NamedTextColor.RED, nickname));
            return;
        }

        if (ctx.authCache().findAuthorizedPlayer(playerUuid).isEmpty()) {
            source.sendMessage(ctx.messages().component(
                    "admin.cache_reset.player_not_found", NamedTextColor.RED, nickname));
            return;
        }

        ctx.authCache().removeAuthorizedPlayer(playerUuid);
        ctx.authCache().endSession(playerUuid);
        source.sendMessage(ctx.messages().component(
                "admin.cache_reset.player", NamedTextColor.GREEN, nickname));
    }

    private void processStatsCommand(CommandSource source) {
        if (!ctx.ensureDatabaseConnected(source, "Admin stats command")) {
            return;
        }

        var totalF = ctx.databaseManager().getTotalRegisteredAccounts();
        var premiumF = ctx.databaseManager().getTotalPremiumAccounts();
        var nonPremiumF = ctx.databaseManager().getTotalNonPremiumAccounts();

        try {
            java.util.concurrent.CompletableFuture.allOf(totalF, premiumF, nonPremiumF).join();
        } catch (CompletionException e) {
            ctx.logger().error(DB_MARKER, "Failed to fetch player statistics", e);
            ctx.sendDatabaseErrorMessage(source);
            return;
        }
        if (!ctx.ensureDatabaseConnected(source, "Admin stats command")) {
            return;
        }

        int total = totalF.join();
        int premium = premiumF.join();
        int nonPremium = nonPremiumF.join();

        var cacheStats = ctx.authCache().getStats();
        int dbCacheSize = ctx.databaseManager().getCacheSize();
        String dbStatus = ctx.databaseManager().isConnected()
                ? ctx.messages().get("database.connected")
                : ctx.messages().get("database.disconnected");

        StringBuilder statsMessage = new StringBuilder();
        statsMessage.append(ctx.messages().get("admin.stats.header")).append("\n");
        statsMessage.append(ctx.messages().get("admin.stats.premium_accounts", premium)).append("\n");
        statsMessage.append(ctx.messages().get("admin.stats.nonpremium_accounts", nonPremium)).append("\n");
        statsMessage.append(ctx.messages().get("admin.stats.total_accounts", total)).append("\n");
        double pct = total > 0 ? (premium * 100.0 / total) : 0.0;
        statsMessage.append(ctx.messages().get("admin.stats.premium_percentage", pct)).append("\n");
        statsMessage.append(ctx.messages().get("admin.stats.authorized_players", cacheStats.authorizedPlayersCount())).append("\n");
        statsMessage.append(ctx.messages().get("admin.stats.premium_cache", cacheStats.premiumCacheCount())).append("\n");
        statsMessage.append(ctx.messages().get("admin.stats.database_cache", dbCacheSize)).append("\n");
        statsMessage.append(ctx.messages().get("admin.stats.database_status", (Object) dbStatus));

        CommandHelper.sendWarning(source, statsMessage.toString());
    }

    private PlayerLookupResult resolvePlayerUuid(CommandSource source, String nickname) {
        return ctx.plugin().getServer().getPlayer(nickname)
                .map(player -> new PlayerLookupResult(player.getUniqueId(), false))
                .orElseGet(() -> resolveRegisteredPlayerUuid(source, nickname));
    }

    private PlayerLookupResult resolveRegisteredPlayerUuid(CommandSource source, String nickname) {
        DatabaseManager.DbResult<RegisteredPlayer> dbResult;
        try {
            dbResult = ctx.databaseManager().findPlayerByNickname(nickname).join();
        } catch (CompletionException e) {
            ctx.logger().error(DB_MARKER, "Failed to look up player {} for cache reset", nickname, e);
            ctx.sendDatabaseErrorMessage(source);
            return new PlayerLookupResult(null, true);
        }
        if (ctx.handleDatabaseError(dbResult, source, nickname, "Admin cache reset lookup")) {
            return new PlayerLookupResult(null, true);
        }

        RegisteredPlayer registeredPlayer = dbResult.getValue();
        if (registeredPlayer == null) {
            return new PlayerLookupResult(null, false);
        }

        try {
            return new PlayerLookupResult(UUID.fromString(registeredPlayer.getUuid()), false);
        } catch (IllegalArgumentException e) {
            source.sendMessage(ctx.messages().component("admin.uuid_invalid", NamedTextColor.RED));
            return new PlayerLookupResult(null, true);
        }
    }

    private void sendLocalizedReloadWarning(CommandSource source, java.util.Set<String> pendingChanges) {
        if (pendingChanges.isEmpty()) {
            return;
        }
        source.sendMessage(ctx.messages().component(
                RELOAD_WARNING_KEY,
                NamedTextColor.YELLOW,
                String.join(", ", pendingChanges)));
    }

    private record PlayerLookupResult(UUID playerUuid, boolean handledError) {}
}
