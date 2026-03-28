package l3gh.inv.mod;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal Netty WebSocket server.
 * Uses Minecraft's bundled Netty — no extra dependencies.
 *
 * This whole section will sadly have to be rewritten as it only works on my machine..
 * 
 * Clients connect to: ws://<ip>:<port>/ws
 * First message must be: {"token":"<secret>"}
 * On success server replies: {"auth":"ok"}
 * After that the server only pushes, clients don't send anything.
 */
public class WsServer {
    private final String secret;
    private final int port;

    // Authenticated, connected channels. Package-private so WsFrameHandler can access it.
    final Set<Channel> clients = ConcurrentHashMap.newKeySet();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public WsServer(String secret, int port) {
        this.secret = secret;
        this.port   = port;
    }

    public void start() throws InterruptedException {
        // 1 thread to accept connections, 2 to handle I/O — plenty for 6 players
        bossGroup  = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast(new HttpServerCodec());
                    p.addLast(new HttpObjectAggregator(65536));
                    // handles the HTTP → WebSocket upgrade handshake
                    p.addLast(new WebSocketServerProtocolHandler("/ws", null, true));
                    // our handler receives only WebSocket text frames after upgrade
                    p.addLast(new WsFrameHandler(secret, clients));
                }
            })
            .bind(port)
            .sync(); // blocks until the port is bound (throws if it can't)

        InvMod.LOGGER.info("inv-mod: WebSocket server listening on :{}", port);
    }

    /**
     * Broadcasts a JSON string to all authenticated connected clients.
     * Safe to call from any thread.
     */
    public void broadcast(String json) {
        if (clients.isEmpty()) return;

        // retainedDuplicate() lets us write the same buffer to multiple channels
        // without re-allocating for each one
        TextWebSocketFrame frame = new TextWebSocketFrame(json);
        for (Channel ch : clients) {
            if (ch.isActive()) {
                ch.writeAndFlush(frame.retainedDuplicate());
            }
        }
        frame.release(); // release the original after we've duplicated for everyone
    }

    public void stop() {
        if (bossGroup  != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        InvMod.LOGGER.info("inv-mod: WebSocket server stopped");
    }
}
