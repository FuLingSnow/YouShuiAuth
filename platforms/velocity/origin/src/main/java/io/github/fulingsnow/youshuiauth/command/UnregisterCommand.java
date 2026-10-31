package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.command.SimpleCommand;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthSender;

/** Velocity adapter delegating to the platform-independent unregister command. */
public class UnregisterCommand implements SimpleCommand {

    private final UnregisterCommandCore delegate;

    public UnregisterCommand(CommandContext ctx) {
        this.delegate = new UnregisterCommandCore(ctx);
    }

    @Override
    public void execute(Invocation invocation) {
        delegate.execute(VelocityAuthSender.of(invocation.source()), invocation.arguments());
    }
}
