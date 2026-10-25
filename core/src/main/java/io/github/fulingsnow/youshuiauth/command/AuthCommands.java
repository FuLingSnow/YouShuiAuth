package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import io.github.fulingsnow.youshuiauth.platform.AuthServices;

/**
 * 平台侧命令门面：把 {@link AuthSender} 与参数转交给命令实现，
 * 使 Paper 等平台不必接触包内的 {@link CommandContext}。
 */
public final class AuthCommands {

    private final CommandContext ctx;
    private final ChangePasswordCommandCore changePassword;
    private final SetPasswordCommandCore setPassword;
    private final LoginCommandCore login;
    private final RegisterCommandCore register;
    private final LogoutCommandCore logout;
    private final TwoFactorCommandCore twoFactor;
    private final UnregisterCommandCore unregister;
    private final VAuthCommandCore admin;

    public AuthCommands(AuthServices services, DatabaseManager databaseManager, AuthCache authCache,
                        Settings settings, Messages messages) {
        this.ctx = new CommandContext(services, databaseManager, authCache, settings, messages);
        this.changePassword = new ChangePasswordCommandCore(ctx);
        this.setPassword = new SetPasswordCommandCore(ctx);
        this.login = new LoginCommandCore(ctx);
        this.register = new RegisterCommandCore(ctx);
        this.logout = new LogoutCommandCore(ctx);
        this.twoFactor = new TwoFactorCommandCore(ctx);
        this.unregister = new UnregisterCommandCore(ctx);
        this.admin = new VAuthCommandCore(ctx);
    }

    public boolean canLogin(AuthSender sender) {
        return login.hasPermission(sender);
    }

    public boolean canRegister(AuthSender sender) {
        return register.hasPermission(sender);
    }

    public boolean canChangePassword(AuthSender sender) {
        return changePassword.hasPermission(sender);
    }

    public boolean canSetPassword(AuthSender sender) {
        return setPassword.hasPermission(sender);
    }

    public boolean canUseTwoFactor(AuthSender sender) {
        return twoFactor.hasPermission(sender);
    }

    public void login(AuthSender sender, String[] args) {
        login.execute(sender, args);
    }

    public void register(AuthSender sender, String[] args) {
        register.execute(sender, args);
    }

    public void changePassword(AuthSender sender, String[] args) {
        changePassword.execute(sender, args);
    }

    public void setPassword(AuthSender sender, String[] args) {
        setPassword.execute(sender, args);
    }

    public void logout(AuthSender sender, String[] args) {
        logout.execute(sender, args);
    }

    public void twoFactor(AuthSender sender, String[] args) {
        twoFactor.execute(sender, args);
    }

    public void unregister(AuthSender sender, String[] args) {
        unregister.execute(sender, args);
    }

    public void admin(AuthSender sender, String[] args) {
        admin.execute(sender, args);
    }

    public java.util.List<String> adminSuggestions(String[] args) {
        return admin.suggest(args);
    }

    public java.util.List<String> twoFactorSuggestions(String[] args) {
        return twoFactor.suggest(args);
    }

    public void shutdown() {
        ctx.shutdown();
    }
}
