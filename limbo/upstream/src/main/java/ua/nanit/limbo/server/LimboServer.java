/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.util.ResourceLeakDetector;
import lombok.Getter;
import ua.nanit.limbo.configuration.LimboConfig;
import ua.nanit.limbo.connection.ClientChannelInitializer;
import ua.nanit.limbo.connection.ClientConnection;
import ua.nanit.limbo.connection.PacketHandler;
import ua.nanit.limbo.connection.PacketSnapshots;
import ua.nanit.limbo.world.DimensionRegistry;

import java.nio.file.Paths;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Getter
public final class LimboServer {

    private LimboConfig config;
    private PacketHandler packetHandler;
    private Connections connections;
    private DimensionRegistry dimensionRegistry;
    private ScheduledFuture<?> keepAliveTask;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private CommandManager commandManager;

    // HybridVelocity patch: when embedded inside the proxy there is no standalone process to own,
    // so the interactive command manager (which reads System.in and would fight the proxy console),
    // the JVM shutdown hook and the explicit System.gc() are all skipped. Lifecycle is driven by
    // VelocityServer instead. See docs/limbo.md.
    private boolean embedded;
    private java.net.SocketAddress pendingAddress;
    private ua.nanit.limbo.server.data.InfoForwarding pendingForwarding;

    public void start() throws Exception {
        start(Paths.get("./"));
    }

    public void start(java.nio.file.Path workingDirectory) throws Exception {
        config = new LimboConfig(workingDirectory);
        if (pendingAddress != null) {
            config.override(pendingAddress, pendingForwarding);
        }
        config.load();

        Log.setLevel(config.getDebugLevel());
        Log.info("Starting server...");

        if (System.getProperty("io.netty.leakDetectionLevel") == null && System.getProperty("io.netty.leakDetection.level") == null) {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        }

        packetHandler = new PacketHandler(this);
        dimensionRegistry = new DimensionRegistry(this);
        dimensionRegistry.load();
        connections = new Connections(config);

        PacketSnapshots.initPackets(this);

        startBootstrap();

        keepAliveTask = workerGroup.scheduleAtFixedRate(this::broadcastKeepAlive, 0L, 5L, TimeUnit.SECONDS);

        if (!embedded) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "NanoLimbo shutdown thread"));
        }

        Log.info("Server started on %s", config.getAddress());

        if (!embedded) {
            commandManager = new CommandManager();
            commandManager.registerAll(this);
            commandManager.start();

            System.gc();
        }
    }

    /**
     * HybridVelocity patch: run without the standalone console, shutdown hook and System.gc(), with
     * the bind address and forwarding supplied by the proxy rather than read from settings.yml.
     */
    public void startEmbedded(java.nio.file.Path workingDirectory, java.net.SocketAddress address,
            ua.nanit.limbo.server.data.InfoForwarding forwarding) throws Exception {
        this.embedded = true;
        this.pendingAddress = address;
        this.pendingForwarding = forwarding;
        start(workingDirectory);
    }

    private void startBootstrap() {
        TransportType transportType = config.getTransportType();
        if (!transportType.isAvailable()) {
            Log.debug("Transport type " + transportType.name() + " is not available! Using NIO.");
            transportType = TransportType.NIO;
        }

        Log.debug("Using " + transportType.name() + " transport type");

        ChannelFactory<? extends ServerChannel> channelFactory = transportType.getChannelFactory();
        IoHandlerFactory ioHandlerFactory = transportType.getIoHandlerFactory();

        bossGroup = new MultiThreadIoEventLoopGroup(config.getBossGroupSize(), ioHandlerFactory);
        workerGroup = new MultiThreadIoEventLoopGroup(config.getWorkerGroupSize(), ioHandlerFactory);

        new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(channelFactory)
                .childHandler(new ClientChannelInitializer(this))
                .childOption(ChannelOption.TCP_NODELAY, true)
                .localAddress(config.getAddress())
                .bind();
    }

    private void broadcastKeepAlive() {
        connections.getAllConnections().forEach(ClientConnection::sendKeepAlive);
    }

    // HybridVelocity patch: was private; the proxy drives shutdown explicitly.
    public void stop() {
        Log.info("Stopping server...");

        if (keepAliveTask != null) {
            keepAliveTask.cancel(true);
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        Log.info("Server stopped, Goodbye!");
    }
}
