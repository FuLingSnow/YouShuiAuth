package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.command.SimpleCommand;
import java.util.List;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthSender;

/** Velocity adapter delegating to the platform-independent admin command tree. */
public class VAuthCommand implements SimpleCommand {

    private final VAuthCommandCore delegate;

    public VAuthCommand(CommandContext ctx) {
        this.delegate = new VAuthCommandCore(ctx);
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
