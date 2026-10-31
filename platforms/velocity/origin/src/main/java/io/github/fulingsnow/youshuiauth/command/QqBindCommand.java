package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.command.SimpleCommand;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthSender;

/** Velocity adapter delegating to the platform-independent QQ-bind command. */
public class QqBindCommand implements SimpleCommand {

    private final QqBindCommandCore delegate;

    public QqBindCommand(io.github.fulingsnow.youshuiauth.command.QqAuthService service,
                         io.github.fulingsnow.youshuiauth.i18n.Messages messages) {
        this.delegate = new QqBindCommandCore(service, messages);
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
