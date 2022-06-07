/*
 * Copyright 2022 Accenture Global Solutions Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.finos.tracdap.svc.data;

import org.finos.tracdap.api.TrustedMetadataApiGrpc;
import org.finos.tracdap.config.DataServiceConfig;
import org.finos.tracdap.config.PlatformConfig;

import org.finos.tracdap.common.codec.CodecManager;
import org.finos.tracdap.common.codec.ICodecManager;
import org.finos.tracdap.common.config.ConfigManager;
import org.finos.tracdap.common.concurrent.ExecutionRegister;
import org.finos.tracdap.common.exception.EPluginNotAvailable;
import org.finos.tracdap.common.exception.EStartup;
import org.finos.tracdap.common.exception.EStorageConfig;
import org.finos.tracdap.common.plugin.PluginManager;
import org.finos.tracdap.common.service.CommonServiceBase;
import org.finos.tracdap.common.storage.IStorageManager;
import org.finos.tracdap.common.storage.StorageManager;
import org.finos.tracdap.svc.data.api.TracDataApi;
import org.finos.tracdap.svc.data.service.DataService;
import org.finos.tracdap.svc.data.service.FileService;

import io.grpc.*;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.arrow.memory.NettyAllocationManager;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.memory.util.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;


public class TracDataService extends CommonServiceBase {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final PluginManager pluginManager;
    private final ConfigManager configManager;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ManagedChannel clientChannel;
    private Server server;

    public TracDataService(PluginManager plugins, ConfigManager config) {
        this.pluginManager = plugins;
        this.configManager = config;
    }

    @Override
    protected void doStartup(Duration startupTimeout) {

        PlatformConfig platformConfig;
        DataServiceConfig dataSvcConfig;

        // Force initialization of Arrow MemoryUtil, rather than waiting until the first API call
        // This can fail on Java versions >= 16 if the java.nio module is not marked as open

        try {
            if (MemoryUtil.UNSAFE == null)
                throw new NullPointerException("MemoryUtil.UNSAFE == null");
        }
        catch (RuntimeException e) {

            log.error("Failed to set up native memory access for Apache Arrow", e);
            throw new EStartup("Failed to set up native memory access for Apache Arrow", e);
        }

        try {
            pluginManager.initRegularPlugins();
        }
        catch (Exception e) {
            var errorMessage = "There was a problem loading the plugins: " + e.getMessage();
            log.error(errorMessage, e);
            throw new EStartup(errorMessage, e);
        }

        try {
            log.info("Loading TRAC platform config...");

            platformConfig = configManager.loadRootConfigObject(PlatformConfig.class);
            dataSvcConfig = platformConfig.getServices().getData();

            // TODO: Config validation

            log.info("Config looks ok");
        }
        catch (Exception e) {

            var errorMessage = "There was a problem loading the platform config: " + e.getMessage();
            log.error(errorMessage, e);
            throw new EStartup(errorMessage, e);
        }

        try {

            var channelType = NioServerSocketChannel.class;
            var clientChannelType = NioSocketChannel.class;

            var workerThreads = Runtime.getRuntime().availableProcessors() * 2;
            workerGroup = new NioEventLoopGroup(workerThreads, new DefaultThreadFactory("data-svc"));
            bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("data-boss"));

            var execRegister = new ExecutionRegister(workerGroup);

            // TODO: Review setup of Arrow allocator, inc. interaction with Netty / Protobuf allocators

            var arrowAllocatorConfig = RootAllocator
                    .configBuilder()
                    .allocationManagerFactory(NettyAllocationManager.FACTORY)
                    .build();

            var arrowAllocator = new RootAllocator(arrowAllocatorConfig);

            var formats = new CodecManager(pluginManager);
            var storage = new StorageManager(pluginManager);
            storage.initStorage(dataSvcConfig.getStorageMap(), formats);

            // Check default storage and format are available
            checkDefaultStorageAndFormat(storage, formats, dataSvcConfig);

            var metaClient = prepareMetadataClient(platformConfig, clientChannelType);

            var dataSvc = new DataService(dataSvcConfig, arrowAllocator, storage, formats, metaClient);
            var fileSvc = new FileService(dataSvcConfig, storage, metaClient);
            var publicApi = new TracDataApi(dataSvc, fileSvc);

            // Create the main server

            this.server = NettyServerBuilder
                    .forPort(dataSvcConfig.getPort())
                    .addService(publicApi)

                    .channelType(channelType)
                    .bossEventLoopGroup(bossGroup)
                    .workerEventLoopGroup(workerGroup)
                    .directExecutor()
                    .intercept(execRegister.registerExecContext())

                    .build();

            // Good to go, let's start!
            server.start();

            log.info("Data service is listening on port {}", server.getPort());
        }
        catch (IOException e) {

            throw new EStartup(e.getMessage(), e);
        }
    }

    private void checkDefaultStorageAndFormat(IStorageManager storage, ICodecManager formats, DataServiceConfig config) {

        try {

            storage.getFileStorage(config.getDefaultStorageKey());
            storage.getDataStorage(config.getDefaultStorageKey());
            formats.getCodec(config.getDefaultStorageFormat());
        }
        catch (EStorageConfig e) {

            var msg = String.format("Storage not configured for default storage key: [%s]", config.getDefaultStorageKey());
            log.error(msg);
            throw new EStartup(msg, e);
        }
        catch (EPluginNotAvailable e) {

            var msg = String.format("Codec not available for default storage format: [%s]", config.getDefaultStorageFormat());
            log.error(msg);
            throw new EStartup(msg, e);
        }
    }

    private TrustedMetadataApiGrpc.TrustedMetadataApiFutureStub
    prepareMetadataClient(
            PlatformConfig platformConfig,
            Class<? extends io.netty.channel.Channel> channelType) {

        var metaInstances = platformConfig.getInstances().getMetaList();

        if (metaInstances.isEmpty()) {

            var err = "Configuration contains no instances of the metadata service";
            log.error(err);
            throw new EStartup(err);
        }

        var metaInstance = metaInstances.get(0);  // Just use the first instance for now

        log.info("Using metadata service instance at [{}:{}]",
                metaInstance.getHost(), metaInstance.getPort());

        var clientChannelBuilder = NettyChannelBuilder
                .forAddress(metaInstance.getHost(), metaInstance.getPort())
                .channelType(channelType)
                .eventLoopGroup(workerGroup)
                .directExecutor()
                .usePlaintext();

        clientChannel = EventLoopChannel.wrapChannel(clientChannelBuilder, workerGroup);

        return TrustedMetadataApiGrpc.newFutureStub(clientChannel);
    }

    @Override
    protected int doShutdown(Duration shutdownTimeout) {

        var deadline = Instant.now().plus(shutdownTimeout);

        var serverDown = shutdownResource("Data service server", deadline, remaining -> {

            server.shutdown();
            return server.awaitTermination(remaining.toMillis(), TimeUnit.MILLISECONDS);
        });

        var clientDown = shutdownResource("Metadata service client", deadline, remaining -> {

            clientChannel.shutdown();
            return clientChannel.awaitTermination(remaining.toMillis(), TimeUnit.MILLISECONDS);
        });

        var workersDown = shutdownResource("Worker thread pool", deadline, remaining -> {

            workerGroup.shutdownGracefully(0, remaining.toMillis(), TimeUnit.MILLISECONDS);
            return workerGroup.awaitTermination(remaining.toMillis(), TimeUnit.MILLISECONDS);
        });

        var bossDown = shutdownResource("Boss thread pool", deadline, remaining -> {

            bossGroup.shutdownGracefully(0, remaining.toMillis(), TimeUnit.MILLISECONDS);
            return bossGroup.awaitTermination(remaining.toMillis(), TimeUnit.MILLISECONDS);
        });

        if (serverDown && clientDown && workersDown && bossDown)
            return 0;

        if (!serverDown)
            server.shutdownNow();

        if (!clientDown)
            clientChannel.shutdownNow();

        return -1;
    }

    public static void main(String[] args) {

        CommonServiceBase.svcMain(TracDataService.class, args);
    }
}