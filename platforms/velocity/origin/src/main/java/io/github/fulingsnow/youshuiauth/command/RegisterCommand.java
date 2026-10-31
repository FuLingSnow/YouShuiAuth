package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.command.SimpleCommand;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthSender;

/** Velocity adapter delegating to the platform-independent register command. */
public class RegisterCommand implements SimpleCommand {

    private final RegisterCommandCore delegate;

    public RegisterCommand(CommandContext ctx) {
        this.delegate = new RegisterCommandCore(ctx);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return delegate.hasPermission(VelocityAuthSender.of(invocation.source()));
    }

    @Override
    public void execute(Invocation invocation) {
        delegate.execute(VelocityAuthSender.of(invocation.source()), invocation.arguments());
    }
}
