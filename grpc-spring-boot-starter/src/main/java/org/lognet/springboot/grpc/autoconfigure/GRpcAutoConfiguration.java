package org.lognet.springboot.grpc.autoconfigure;

import io.grpc.ServerBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.services.HealthStatusManager;
import lombok.extern.slf4j.Slf4j;
import org.lognet.springboot.grpc.GRpcGlobalInterceptor;
import org.lognet.springboot.grpc.GRpcServerBuilderConfigurer;
import org.lognet.springboot.grpc.GRpcServerRunner;
import org.lognet.springboot.grpc.GRpcService;
import org.lognet.springboot.grpc.validation.ValidatingInterceptor;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.util.SocketUtils;

import javax.validation.Validator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Created by alexf on 25-Jan-16.
 */

@AutoConfigureOrder
@AutoConfigureAfter(ValidationAutoConfiguration.class)
@ConditionalOnBean(annotation = GRpcService.class)
@EnableConfigurationProperties(GRpcServerProperties.class)
@Slf4j
public class GRpcAutoConfiguration {

    @Autowired
    private GRpcServerProperties grpcServerProperties;

    @Bean
    @ConditionalOnProperty(value = "grpc.enabled", havingValue = "true", matchIfMissing = true)
    public GRpcServerRunner grpcServerRunner(@Qualifier("grpcInternalConfigurator") Consumer<ServerBuilder<?>> configurator) {
        ServerBuilder<?> serverBuilder = Optional.ofNullable(grpcServerProperties.getNettyServer())
                .<ServerBuilder<?>> map(n->{
                    final NettyServerBuilder builder = Optional.ofNullable(n.getPrimaryListenAddress())
                            .map(NettyServerBuilder::forAddress)
                            .orElse(NettyServerBuilder.forPort(grpcServerProperties.getRunningPort()));

                    Optional.ofNullable(n.getAdditionalListenAddresses())
                            .ifPresent(l->l.forEach(builder::addListenAddress));

                    Optional.ofNullable(n.getFlowControlWindow())
                            .ifPresent(builder::flowControlWindow);

                    Optional.ofNullable(n.getInitialFlowControlWindow())
                            .ifPresent(builder::initialFlowControlWindow);

                    Optional.ofNullable(n.getKeepAliveTime())
                            .ifPresent(t->builder.keepAliveTime(t.toMillis(), TimeUnit.MILLISECONDS));

                    Optional.ofNullable(n.getKeepAliveTimeout())
                            .ifPresent(t->builder.keepAliveTimeout(t.toMillis(), TimeUnit.MILLISECONDS));

                    Optional.ofNullable(n.getPermitKeepAliveTime())
                            .ifPresent(t->builder.permitKeepAliveTime(t.toMillis(), TimeUnit.MILLISECONDS));


                    Optional.ofNullable(n.getMaxConnectionAge())
                            .ifPresent(t->builder.maxConnectionAge(t.toMillis(), TimeUnit.MILLISECONDS));

                    Optional.ofNullable(n.getMaxConnectionAgeGrace())
                            .ifPresent(t->builder.maxConnectionAgeGrace(t.toMillis(), TimeUnit.MILLISECONDS));

                    Optional.ofNullable(n.getMaxConnectionIdle())
                            .ifPresent(t->builder.maxConnectionIdle(t.toMillis(), TimeUnit.MILLISECONDS));

                    Optional.ofNullable(n.getMaxConcurrentCallsPerConnection())
                            .ifPresent(builder::maxConcurrentCallsPerConnection);

                    Optional.ofNullable(n.getPermitKeepAliveWithoutCalls())
                            .ifPresent(builder::permitKeepAliveWithoutCalls);

                    Optional.ofNullable(n.getMaxInboundMessageSize())
                            .ifPresent(s->builder.maxInboundMessageSize((int)s.toBytes()));

                    Optional.ofNullable(n.getMaxInboundMetadataSize())
                            .ifPresent(s->builder.maxInboundMetadataSize((int)s.toBytes()));


                    return builder;

                })
                .orElse(ServerBuilder.forPort(grpcServerProperties.getRunningPort()));
        return new GRpcServerRunner(configurator, serverBuilder);
    }

    @Bean
    @ConditionalOnExpression("#{environment.getProperty('grpc.inProcessServerName','')!=''}")
    public GRpcServerRunner grpcInprocessServerRunner(@Qualifier("grpcInternalConfigurator") Consumer<ServerBuilder<?>> configurator) {

        return new GRpcServerRunner(configurator, InProcessServerBuilder.forName(grpcServerProperties.getInProcessServerName()));
    }

    @Bean
    public HealthStatusManager healthStatusManager() {
        return new HealthStatusManager();
    }

    @Bean
    @ConditionalOnMissingBean(GRpcServerBuilderConfigurer.class)
    public GRpcServerBuilderConfigurer serverBuilderConfigurer() {
        return new GRpcServerBuilderConfigurer();
    }

    @Bean(name = "grpcInternalConfigurator")
    public Consumer<ServerBuilder<?>> configurator(GRpcServerBuilderConfigurer configurer) {
        return serverBuilder -> {
            if (grpcServerProperties.isEnabled()) {
                Optional.ofNullable(grpcServerProperties.getSecurity())
                        .ifPresent(s -> {
                            boolean setupSecurity = Optional.ofNullable(s.getCertChain()).isPresent();
                            if (setupSecurity != Optional.ofNullable(s.getPrivateKey()).isPresent()) {
                                throw new BeanCreationException("Both  gRPC  TLS 'certChain' and 'privateKey' should be configured. One of them is null. ");
                            }
                            if (setupSecurity) {
                                try {
                                    serverBuilder.useTransportSecurity(s.getCertChain().getInputStream(),
                                            s.getPrivateKey().getInputStream()
                                    );
                                } catch (IOException e) {
                                    throw new BeanCreationException("Failed to setup security", e);
                                }
                            }
                        });
            }
            configurer.configure(serverBuilder);
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    public static Converter<String, InetSocketAddress> socketAddressConverter() {
        return new Converter<String, InetSocketAddress>() {
            @Override
            public InetSocketAddress convert(String source) {
                final String[] chunks = source.split(":");
                int port;
                switch (chunks.length) {
                    case 1:
                        port = GRpcServerProperties.DEFAULT_GRPC_PORT;
                        break;
                    case 2:
                        port = Integer.parseInt(chunks[1]);
                        if(port<1){
                            port = SocketUtils.findAvailableTcpPort();
                        }
                        break;
                    default:
                        throw new IllegalArgumentException(source +" can't be converted to socket address");

                }

                return new InetSocketAddress(chunks[0], port);
            }
        };
    }

    @Bean
    @ConditionalOnClass(Validator.class)
    @ConditionalOnBean(Validator.class)
    @GRpcGlobalInterceptor
    public ValidatingInterceptor validatingInterceptor(@Lazy Validator validator){
        return  new ValidatingInterceptor(validator);
    }


}
