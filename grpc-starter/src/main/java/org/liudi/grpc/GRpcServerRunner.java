package org.liudi.grpc;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import org.liudi.grpc.autoconfigure.GRpcServerProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;

/**
 *  Hosts embedded gRPC server.
 */
public class GRpcServerRunner implements CommandLineRunner,DisposableBean {

    /**
     * Name of static function of gRPC service-outer class that creates {@link io.grpc.ServerServiceDefinition}.
     */
    final private  static String bindServiceMethodName = "bindService";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private GRpcServerProperties gRpcServerProperties;

    private Server server;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Starting gRPC Server ...");

        final ServerBuilder<?> serverBuilder = ServerBuilder.forPort(gRpcServerProperties.getPort());

        // find and register all GRpcService-enabled beans
        for(Object grpcService : applicationContext.getBeansWithAnnotation(GRpcService.class).values()) {
            final Class<?> grpcServiceOuterClass = AnnotationUtils.findAnnotation(grpcService.getClass(), GRpcService.class).grpcServiceOuterClass();

            // find 'bindService' method on outer class.
            final Optional<Method> bindServiceMethod = Arrays.asList(ReflectionUtils.getAllDeclaredMethods(grpcServiceOuterClass)).stream().filter(
                    method ->  bindServiceMethodName.equals(method.getName()) && 1 == method.getParameterCount() && method.getParameterTypes()[0].isAssignableFrom(grpcService.getClass())
            ).findFirst();

            // register service
            if (bindServiceMethod.isPresent()) {
                ServerServiceDefinition serviceDefinition = (ServerServiceDefinition) bindServiceMethod.get().invoke(null, grpcService);
                serverBuilder.addService(serviceDefinition);
                System.out.println("'{}' service has been registered." +serviceDefinition.getName());
            } else {
                throw new IllegalArgumentException(String.format("Failed to find '%s' method on class %s.\r\n" +
                                "Please make sure you've provided correct 'grpcServiceOuterClass' attribute for '%s' annotation.\r\n" +
                                "It should be the protoc-generated outer class of your service."
                        , bindServiceMethodName,grpcServiceOuterClass.getName(), GRpcService.class.getName()));
            }
        }

        server = serverBuilder.build().start();
        System.out.println("gRPC Server started, listening on port {}." + gRpcServerProperties.getPort());
        startDaemonAwaitThread();

    }

    private void startDaemonAwaitThread() {
        Thread awaitThread = new Thread() {
            @Override
            public void run() {
                try {
                    GRpcServerRunner.this.server.awaitTermination();
                } catch (InterruptedException e) {
                    System.out.println("gRPC server stopped." +e);
                }
            }

        };
        awaitThread.setDaemon(false);
        awaitThread.start();
    }
    @Override
    public void destroy() throws Exception {
        System.out.println("Shutting down gRPC server ...");
        Optional.ofNullable(server).ifPresent(Server::shutdown);
        System.out.println("gRPC server stopped.");
    }
}
