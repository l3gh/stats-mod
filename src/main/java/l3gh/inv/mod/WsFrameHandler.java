package l3gh.inv.mod;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.Set;

/**
 * Handles WebSocket frames for a single client connection.
 *
 * Auth flow:
 *   1. Client connects and upgrades to WebSocket
 *   2. Client sends: {"token":"<secret>"}
 *   3. If correct: server replies {"auth":"ok"} and adds channel to broadcast set
 *   4. If wrong:   server closes connection immediately
 *
 * After auth, the server only pushes inventory JSON — it ignores any further
 * messages from the client.
 */
public class WsFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final String secret;
    private final Set<Channel> clients;
    private boolean authenticated = false;

    public WsFrameHandler(String secret, Set<Channel> clients) {
        this.secret  = secret;
        this.clients = clients;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        if (authenticated) return; // push-only after auth; ignore anything the client sends

        try {
            JsonObject msg = JsonParser.parseString(frame.text()).getAsJsonObject();
            String token = msg.get("token").getAsString();

            if (secret.equals(token)) {
                authenticated = true;
                clients.add(ctx.channel());
                ctx.writeAndFlush(new TextWebSocketFrame("{\"auth\":\"ok\"}"));
                InvMod.LOGGER.info("inv-mod: client authenticated from {}", ctx.channel().remoteAddress());
                // immediately push current state of all online players to this new client
                InvMod.pushAllOnlinePlayers(ctx.channel());
            } else {
                InvMod.LOGGER.warn("inv-mod: bad token from {} — closing", ctx.channel().remoteAddress());
                ctx.close();
            }
        } catch (Exception e) {
            InvMod.LOGGER.warn("inv-mod: malformed auth message from {} — closing", ctx.channel().remoteAddress());
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        clients.remove(ctx.channel());
        InvMod.LOGGER.info("inv-mod: client disconnected from {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        InvMod.LOGGER.error("inv-mod: channel error from {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }
}
