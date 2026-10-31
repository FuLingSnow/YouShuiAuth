package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.command.SimpleCommand;
import java.util.List;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthSender;

/** Velocity adapter delegating to the platform-independent two-factor command. */
public class TwoFactorCommand implements SimpleCommand {

    private final TwoFactorCommandCore delegate;

    public TwoFactorCommand(CommandContext ctx) {
        this.delegate = new TwoFactorCommandCore(ctx);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return delegate.hasPermission(VelocityAuthSender.of(invocation.source()));
    }

    @Override
    public void execute(Invocation invocation) {
        delegate.execute(VelocityAuthSender.of(invocation.source()), invocation.arguments());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return delegate.suggest(invocation.arguments());
    }
}
