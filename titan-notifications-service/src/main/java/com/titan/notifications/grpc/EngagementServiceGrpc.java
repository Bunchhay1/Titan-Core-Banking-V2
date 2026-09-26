package com.titan.notifications.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: engagement.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class EngagementServiceGrpc {

  private EngagementServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "titan.ai.EngagementService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.titan.notifications.grpc.EngagementProto.EngagementRequest,
      com.titan.notifications.grpc.EngagementProto.EngagementResponse> getGetOptimalDeliveryTimeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetOptimalDeliveryTime",
      requestType = com.titan.notifications.grpc.EngagementProto.EngagementRequest.class,
      responseType = com.titan.notifications.grpc.EngagementProto.EngagementResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.titan.notifications.grpc.EngagementProto.EngagementRequest,
      com.titan.notifications.grpc.EngagementProto.EngagementResponse> getGetOptimalDeliveryTimeMethod() {
    io.grpc.MethodDescriptor<com.titan.notifications.grpc.EngagementProto.EngagementRequest, com.titan.notifications.grpc.EngagementProto.EngagementResponse> getGetOptimalDeliveryTimeMethod;
    if ((getGetOptimalDeliveryTimeMethod = EngagementServiceGrpc.getGetOptimalDeliveryTimeMethod) == null) {
      synchronized (EngagementServiceGrpc.class) {
        if ((getGetOptimalDeliveryTimeMethod = EngagementServiceGrpc.getGetOptimalDeliveryTimeMethod) == null) {
          EngagementServiceGrpc.getGetOptimalDeliveryTimeMethod = getGetOptimalDeliveryTimeMethod =
              io.grpc.MethodDescriptor.<com.titan.notifications.grpc.EngagementProto.EngagementRequest, com.titan.notifications.grpc.EngagementProto.EngagementResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetOptimalDeliveryTime"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.notifications.grpc.EngagementProto.EngagementRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.notifications.grpc.EngagementProto.EngagementResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EngagementServiceMethodDescriptorSupplier("GetOptimalDeliveryTime"))
              .build();
        }
      }
    }
    return getGetOptimalDeliveryTimeMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EngagementServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EngagementServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EngagementServiceStub>() {
        @java.lang.Override
        public EngagementServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EngagementServiceStub(channel, callOptions);
        }
      };
    return EngagementServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EngagementServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EngagementServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EngagementServiceBlockingStub>() {
        @java.lang.Override
        public EngagementServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EngagementServiceBlockingStub(channel, callOptions);
        }
      };
    return EngagementServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EngagementServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EngagementServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EngagementServiceFutureStub>() {
        @java.lang.Override
        public EngagementServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EngagementServiceFutureStub(channel, callOptions);
        }
      };
    return EngagementServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void getOptimalDeliveryTime(com.titan.notifications.grpc.EngagementProto.EngagementRequest request,
        io.grpc.stub.StreamObserver<com.titan.notifications.grpc.EngagementProto.EngagementResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetOptimalDeliveryTimeMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service EngagementService.
   */
  public static abstract class EngagementServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return EngagementServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service EngagementService.
   */
  public static final class EngagementServiceStub
      extends io.grpc.stub.AbstractAsyncStub<EngagementServiceStub> {
    private EngagementServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EngagementServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EngagementServiceStub(channel, callOptions);
    }

    /**
     */
    public void getOptimalDeliveryTime(com.titan.notifications.grpc.EngagementProto.EngagementRequest request,
        io.grpc.stub.StreamObserver<com.titan.notifications.grpc.EngagementProto.EngagementResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetOptimalDeliveryTimeMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service EngagementService.
   */
  public static final class EngagementServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<EngagementServiceBlockingStub> {
    private EngagementServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EngagementServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EngagementServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.titan.notifications.grpc.EngagementProto.EngagementResponse getOptimalDeliveryTime(com.titan.notifications.grpc.EngagementProto.EngagementRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetOptimalDeliveryTimeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service EngagementService.
   */
  public static final class EngagementServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<EngagementServiceFutureStub> {
    private EngagementServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EngagementServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EngagementServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.titan.notifications.grpc.EngagementProto.EngagementResponse> getOptimalDeliveryTime(
        com.titan.notifications.grpc.EngagementProto.EngagementRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetOptimalDeliveryTimeMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_OPTIMAL_DELIVERY_TIME = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_OPTIMAL_DELIVERY_TIME:
          serviceImpl.getOptimalDeliveryTime((com.titan.notifications.grpc.EngagementProto.EngagementRequest) request,
              (io.grpc.stub.StreamObserver<com.titan.notifications.grpc.EngagementProto.EngagementResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getGetOptimalDeliveryTimeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.titan.notifications.grpc.EngagementProto.EngagementRequest,
              com.titan.notifications.grpc.EngagementProto.EngagementResponse>(
                service, METHODID_GET_OPTIMAL_DELIVERY_TIME)))
        .build();
  }

  private static abstract class EngagementServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EngagementServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.titan.notifications.grpc.EngagementProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("EngagementService");
    }
  }

  private static final class EngagementServiceFileDescriptorSupplier
      extends EngagementServiceBaseDescriptorSupplier {
    EngagementServiceFileDescriptorSupplier() {}
  }

  private static final class EngagementServiceMethodDescriptorSupplier
      extends EngagementServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    EngagementServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (EngagementServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EngagementServiceFileDescriptorSupplier())
              .addMethod(getGetOptimalDeliveryTimeMethod())
              .build();
        }
      }
    }
    return result;
  }
}
