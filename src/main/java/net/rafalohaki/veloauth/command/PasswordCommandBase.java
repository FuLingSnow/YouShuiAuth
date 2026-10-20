package net.rafalohaki.veloauth.command;

import com.velocitypowered.api.command.SimpleCommand;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

abstract class PasswordCommandBase implements SimpleCommand {

    protected static final String ERROR_DATABASE_QUERY = "error.database.query";
    protected static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");
    protected static final Marker DB_MARKER = MarkerFactory.getMarker("DATABASE");

    protected final CommandContext ctx;

    PasswordCommandBase(CommandContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return CommandHelper.isPlayerAuthorized(invocation, ctx);
    }
}
