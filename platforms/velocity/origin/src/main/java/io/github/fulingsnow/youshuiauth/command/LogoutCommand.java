package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.command.SimpleCommand;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthSender;

/** Velocity adapter delegating to the platform-independent logout command. */
public class LogoutCommand implements SimpleCommand {

    private final LogoutCommandCore delegate;

    public LogoutCommand(CommandContext ctx) {
        this.delegate = new LogoutCommandCore(ctx);
    }

    @Override
    public void execute(Invocation invocation) {
        delegate.execute(VelocityAuthSender.of(invocation.source()), invocation.arguments());
    }
}
