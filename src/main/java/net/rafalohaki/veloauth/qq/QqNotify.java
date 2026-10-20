package net.rafalohaki.veloauth.qq;

/**
 * One pending notification for the bot poller; routing and delivery are the bot's job.
 */
public record QqNotify(String qqId, String message) {
}
