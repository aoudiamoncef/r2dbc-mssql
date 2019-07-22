/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.mssql.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.r2dbc.mssql.client.ssl.TdsSslHandler;
import io.r2dbc.mssql.message.ClientMessage;
import io.r2dbc.mssql.message.Message;
import io.r2dbc.mssql.message.TransactionDescriptor;
import io.r2dbc.mssql.message.header.PacketIdProvider;
import io.r2dbc.mssql.message.tds.ProtocolException;
import io.r2dbc.mssql.message.token.AbstractInfoToken;
import io.r2dbc.mssql.message.token.EnvChangeToken;
import io.r2dbc.mssql.message.token.FeatureExtAckToken;
import io.r2dbc.mssql.message.type.Collation;
import io.r2dbc.mssql.util.Assert;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.netty.Connection;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.TcpClient;
import reactor.util.context.Context;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * An implementation of a TDS client based on the Reactor Netty project.
 *
 * @see TcpClient
 */
public final class ReactorNettyClient implements Client {

    private static final Logger logger = LoggerFactory.getLogger(ReactorNettyClient.class);

    private static final boolean DEBUG_ENABLED = logger.isDebugEnabled();

    private final ConnectionContext context;

    private final ByteBufAllocator byteBufAllocator;

    private final Connection connection;

    private final TdsEncoder tdsEncoder;

    private final Consumer<EnvChangeToken> handleEnvChange;

    private final Consumer<FeatureExtAckToken> featureAckChange = (token) -> {

        for (FeatureExtAckToken.FeatureToken featureToken : token.getFeatureTokens()) {

            if (featureToken instanceof FeatureExtAckToken.ColumnEncryption) {
                this.encryptionSupported = true;
            }
        }
    };

    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    private final EmitterProcessor<ClientMessage> requestProcessor = EmitterProcessor.create(false);

    private final FluxSink<ClientMessage> requests = this.requestProcessor.sink();

    private final EmitterProcessor<Message> responseProcessor = EmitterProcessor.create(false);

    private ConnectionState state = ConnectionState.PRELOGIN;

    private MessageDecoder decodeFunction = ConnectionState.PRELOGIN.decoder(this);

    private boolean encryptionSupported = false;

    private volatile TransactionDescriptor transactionDescriptor = TransactionDescriptor.empty();

    private volatile TransactionStatus transactionStatus = TransactionStatus.AUTO_COMMIT;

    private volatile Optional<Collation> databaseCollation = Optional.empty();

    private TransactionListener transactionListener = new TransactionListener();

    private CollationListener collationListener = new CollationListener();

    /**
     * Creates a new frame processor connected to a given TCP connection.
     *
     * @param connection        the TCP connection
     * @param connectionContext
     */
    private ReactorNettyClient(Connection connection, TdsEncoder tdsEncoder, ConnectionContext connectionContext) {
        Assert.requireNonNull(connection, "Connection must not be null");

        this.context = connectionContext;

        StreamDecoder decoder = new StreamDecoder();

        this.handleEnvChange = (token) -> {

            EnvironmentChangeEvent event = new EnvironmentChangeEvent(token);

            try {
                tdsEncoder.onEnvironmentChange(event);
                transactionListener.onEnvironmentChange(event);
                collationListener.onEnvironmentChange(event);
            } catch (Exception e) {
                logger.warn(this.context.getMessage("Failed onEnvironmentChange() in {}"), "", e);
            }
        };

        this.byteBufAllocator = connection.outbound().alloc();
        this.connection = connection;
        this.tdsEncoder = tdsEncoder;

        connection.addHandlerFirst(new ChannelInboundHandlerAdapter() {

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {

                // Server has closed the connection without us wanting to close it
                // Typically happens if we send data asynchronously (i.e. previous command didn't complete).
                if (ReactorNettyClient.this.isClosed.compareAndSet(false, true)) {
                    logger.warn(ReactorNettyClient.this.context.getMessage("Connection has been closed by peer"));
                }

                super.channelInactive(ctx);
            }
        });

        Consumer<Message> handleStateChange =
            (message) -> {

                ConnectionState connectionState = this.state;

                if (connectionState.canAdvance(message)) {

                    ConnectionState nextState = connectionState.next(message, connection);

                    this.state = nextState;
                    this.decodeFunction = nextState.decoder(this);
                }
            };

        SynchronousSink<Message> sink = new SynchronousSink<Message>() {

            @Override
            public void complete() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Context currentContext() {
                return requestProcessor.currentContext();
            }

            @Override
            public void error(Throwable e) {
                responseProcessor.onError(e);
            }

            @Override
            public void next(Message message) {

                if (DEBUG_ENABLED) {
                    onInfoToken(message);
                }

                handleStateChange.accept(message);

                if (message.getClass() == EnvChangeToken.class) {
                    handleEnvChange.accept((EnvChangeToken) message);
                }

                if (message.getClass() == FeatureExtAckToken.class) {
                    featureAckChange.accept((FeatureExtAckToken) message);
                }

                responseProcessor.onNext(message);
            }
        };

        connection.inbound().receiveObject() //
            .doOnNext(it -> {

                if (it instanceof ByteBuf) {

                    ByteBuf buffer = (ByteBuf) it;
                    decoder.decode(buffer, this.decodeFunction, sink);
                    return;
                }

                if (it instanceof Message) {
                    sink.next((Message) it);
                    return;
                }

                throw ProtocolException.unsupported(String.format("Unexpected protocol message: [%s]", it));
            }).doOnError(e -> {

            logger.warn(this.context.getMessage("Error: {}"), e.getMessage(), e);
            if (e instanceof ProtocolException) {
                this.isClosed.set(true);
                connection.channel().close();
            }
        }).subscribe(new CoreSubscriber<Object>() {

            @Override
            public Context currentContext() {
                return responseProcessor.currentContext();
            }

            @Override
            public void onSubscribe(Subscription s) {
                responseProcessor.onSubscribe(s);
            }

            @Override
            public void onNext(Object message) {

            }

            @Override
            public void onError(Throwable t) {
                responseProcessor.onError(t);
            }

            @Override
            public void onComplete() {
                responseProcessor.onComplete();
            }
        });

        this.requestProcessor
            .concatMap(
                message -> {

                    if (DEBUG_ENABLED) {
                        logger.debug(this.context.getMessage("Request: {}"), message);
                    }

                    Object encoded = message.encode(connection.outbound().alloc(), this.tdsEncoder.getPacketSize());

                    if (encoded instanceof Publisher) {
                        return connection.outbound().sendObject((Publisher) encoded);
                    }

                    return connection.outbound().sendObject(encoded);
                })
            .doOnError(throwable -> {
                logger.warn(this.context.getMessage("Error: {}"), throwable.getMessage(), throwable);
                this.isClosed.set(true);
                connection.channel().close();
            })
            .subscribe();
    }

    private void onInfoToken(Message message) {
        logger.debug(context.getMessage("Response: {}"), message);

        if (message instanceof AbstractInfoToken) {
            AbstractInfoToken token = (AbstractInfoToken) message;
            if (token.getClassification() == AbstractInfoToken.Classification.INFORMATIONAL) {
                logger.debug(context.getMessage("Info: Code [{}] Severity [{}]: {}"), token.getNumber(), token.getClassification(),
                    token.getMessage());
            } else {
                logger.debug(context.getMessage("Warning: Code [{}] Severity [{}]: {}"), token.getNumber(), token.getClassification(),
                    token.getMessage());
            }
        }
    }

    /**
     * Creates a new frame processor connected to a given host.
     *
     * @param host the host to connect to
     * @param port the port to connect to
     */
    public static Mono<ReactorNettyClient> connect(String host, int port) {

        Assert.requireNonNull(host, "host must not be null");

        return connect(host, port, Duration.ofSeconds(30));
    }

    /**
     * Creates a new frame processor connected to a given host.
     *
     * @param host           the host to connect to
     * @param port           the port to connect to
     * @param connectTimeout the connect timeout
     */
    public static Mono<ReactorNettyClient> connect(String host, int port, Duration connectTimeout) {

        Assert.requireNonNull(connectTimeout, "connect timeout must not be null");
        Assert.requireNonNull(host, "host must not be null");

        return connect(new ClientConfiguration() {

            @Override
            public String getHost() {
                return host;
            }

            @Override
            public int getPort() {
                return port;
            }

            @Override
            public Duration getConnectTimeout() {
                return connectTimeout;
            }

            @Override
            public ConnectionProvider getConnectionProvider() {
                return ConnectionProvider.newConnection();
            }

            @Override
            public boolean isSslEnabled() {
                return false;
            }

            @Override
            public String getHostNameInCertificate() {
                return host;
            }
        }, null, null);
    }

    /**
     * Creates a new frame processor connected to {@link ClientConfiguration}.
     *
     * @param configuration   the client configuration
     * @param applicationName
     * @param connectionId
     */
    public static Mono<ReactorNettyClient> connect(ClientConfiguration configuration, @Nullable String applicationName, @Nullable UUID connectionId) {

        Assert.requireNonNull(configuration, "configuration must not be null");

        ConnectionContext connectionContext = new ConnectionContext(applicationName, connectionId);
        logger.debug(connectionContext.getMessage("connect()"));

        PacketIdProvider packetIdProvider = PacketIdProvider.atomic();

        TdsEncoder tdsEncoder = new TdsEncoder(packetIdProvider);

        Mono<? extends Connection> connection = TcpClient.create(configuration.getConnectionProvider())
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(configuration.getConnectTimeout().toMillis()))
            .host(configuration.getHost())
            .port(configuration.getPort())
            .connect()
            .doOnNext(it -> {

                ChannelPipeline pipeline = it.channel().pipeline();
                pipeline.addFirst(tdsEncoder.getClass().getName(), tdsEncoder);

                TdsSslHandler handler = new TdsSslHandler(packetIdProvider, configuration, connectionContext.withChannelId(it.channel().toString()));
                pipeline.addAfter(tdsEncoder.getClass().getName(), handler.getClass().getName(), handler);

                InternalLogger logger = InternalLoggerFactory.getInstance(ReactorNettyClient.class);
                if (logger.isTraceEnabled()) {
                    pipeline.addFirst(LoggingHandler.class.getSimpleName(),
                        new LoggingHandler(ReactorNettyClient.class, LogLevel.TRACE));
                }
            });

        return connection.map(it -> new ReactorNettyClient(it, tdsEncoder, connectionContext.withChannelId(it.channel().toString())));
    }

    @Override
    public Mono<Void> close() {

        logger.debug(this.context.getMessage("close()"));

        return Mono.defer(() -> {

            logger.debug(this.context.getMessage("close(subscribed)"));

            return Mono.create(it -> {

                if (this.isClosed.compareAndSet(false, true)) {

                    this.connection.channel().disconnect().addListener((ChannelFutureListener) future ->
                    {
                        if (future.isSuccess()) {
                            it.success();
                        } else {
                            it.error(future.cause());
                        }
                    });
                } else {
                    it.success();
                }
            });
        });
    }

    @Override
    public Flux<Message> exchange(ClientMessage request) {

        Assert.requireNonNull(request, "Requests must not be null");

        if (DEBUG_ENABLED) {
            logger.debug(String.format(this.context.getMessage("exchange(%s)"), request));
        }

        /*if (this.isClosed.get()) {
            return Flux.error(new IllegalStateException("Cannot exchange messages because the connection is closed"));
        } */

        return this.responseProcessor
            .doOnSubscribe(s -> this.requests.next(request));
    }

    @Override
    public Flux<Message> exchange(Publisher<? extends ClientMessage> requests) {

        Assert.requireNonNull(requests, "Requests must not be null");

        if (DEBUG_ENABLED) {
            logger.debug(this.context.getMessage("exchange()"));
        }

        return Flux.defer(() -> {

            logger.debug(this.context.getMessage("exchange(subscribed)"));

            if (this.isClosed.get()) {
                return Flux.error(new IllegalStateException("Cannot exchange messages because the connection is closed"));
            }

            return this.responseProcessor
                .doOnSubscribe(s -> Flux.from(requests).subscribe(this.requests::next, this.requests::error));
        });
    }

    @Override
    public ByteBufAllocator getByteBufAllocator() {
        return this.byteBufAllocator;
    }

    @Override
    public ConnectionContext getContext() {
        return this.context;
    }

    @Override
    public TransactionDescriptor getTransactionDescriptor() {
        return this.transactionDescriptor;
    }

    @Override
    public TransactionStatus getTransactionStatus() {
        return this.transactionStatus;
    }

    @Override
    public Optional<Collation> getDatabaseCollation() {
        return this.databaseCollation;
    }

    @Override
    public boolean isColumnEncryptionSupported() {
        return this.encryptionSupported;
    }


    @SuppressWarnings("unchecked")
    private static <T extends Message> BiConsumer<Message, SynchronousSink<Message>> handleMessage(Class<T> type,
                                                                                                   BiConsumer<T, SynchronousSink<Message>> consumer) {
        return (message, sink) -> {
            if (type.isInstance(message)) {
                consumer.accept((T) message, sink);
            } else {
                sink.next(message);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends Message> Consumer<Message> handleExact(Class<T> type, Consumer<T> consumer) {
        return (message) -> {
            if (type == message.getClass()) {
                consumer.accept((T) message);
            }
        };
    }

    class TransactionListener implements EnvironmentChangeListener {

        @Override
        public void onEnvironmentChange(EnvironmentChangeEvent event) {

            EnvChangeToken token = event.getToken();

            if (token.getChangeType() == EnvChangeToken.EnvChangeType.BeginTx
                || token.getChangeType() == EnvChangeToken.EnvChangeType.EnlistDTC) {

                byte[] descriptor = token.getNewValue();

                if (descriptor.length != TransactionDescriptor.LENGTH) {
                    throw ProtocolException.invalidTds("Transaction descriptor length mismatch");
                }

                if (DEBUG_ENABLED) {

                    String op;
                    if (token.getChangeType() == EnvChangeToken.EnvChangeType.BeginTx) {
                        op = "started";
                    } else {
                        op = "enlisted";
                    }

                    logger.debug(String.format(ReactorNettyClient.this.context.getMessage("Transaction %s"), op));
                }

                updateStatus(TransactionStatus.STARTED, TransactionDescriptor.from(descriptor));
            }

            if (token.getChangeType() == EnvChangeToken.EnvChangeType.CommitTx) {

                if (DEBUG_ENABLED) {
                    logger.debug(context.getMessage("Transaction committed"));
                }

                updateStatus(TransactionStatus.EXPLICIT, TransactionDescriptor.empty());
            }

            if (token.getChangeType() == EnvChangeToken.EnvChangeType.RollbackTx) {

                if (DEBUG_ENABLED) {
                    logger.debug(context.getMessage("Transaction rolled back"));
                }

                updateStatus(TransactionStatus.EXPLICIT, TransactionDescriptor.empty());
            }
        }

        private void updateStatus(TransactionStatus status, TransactionDescriptor descriptor) {
            ReactorNettyClient.this.transactionStatus = status;
            ReactorNettyClient.this.transactionDescriptor = descriptor;
        }
    }

    class CollationListener implements EnvironmentChangeListener {

        @Override
        public void onEnvironmentChange(EnvironmentChangeEvent event) {

            if (event.getToken().getChangeType() == EnvChangeToken.EnvChangeType.SQLCollation) {

                Collation collation = Collation.decode(Unpooled.wrappedBuffer(event.getToken().getNewValue()));
                ReactorNettyClient.this.databaseCollation = Optional.of(collation);
            }
        }
    }
}
