package com.titan.riskengine;

import static io.grpc.MethodDescriptor.generateFullMethodName;

@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: risk_engine.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RiskEngineServiceGrpc {

  private RiskEngineServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "risk_engine.RiskEngineService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.titan.riskengine.RiskCheckRequest,
      com.titan.riskengine.RiskCheckResponse> getCheckRiskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CheckRisk",
      requestType = com.titan.riskengine.RiskCheckRequest.class,
      responseType = com.titan.riskengine.RiskCheckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.titan.riskengine.RiskCheckRequest,
      com.titan.riskengine.RiskCheckResponse> getCheckRiskMethod() {
    io.grpc.MethodDescriptor<com.titan.riskengine.RiskCheckRequest, com.titan.riskengine.RiskCheckResponse> getCheckRiskMethod;
    if ((getCheckRiskMethod = RiskEngineServiceGrpc.getCheckRiskMethod) == null) {
      synchronized (RiskEngineServiceGrpc.class) {
        if ((getCheckRiskMethod = RiskEngineServiceGrpc.getCheckRiskMethod) == null) {
          RiskEngineServiceGrpc.getCheckRiskMethod = getCheckRiskMethod =
              io.grpc.MethodDescriptor.<com.titan.riskengine.RiskCheckRequest, com.titan.riskengine.RiskCheckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CheckRisk"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.riskengine.RiskCheckRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.riskengine.RiskCheckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RiskEngineServiceMethodDescriptorSupplier("CheckRisk"))
              .build();
        }
      }
    }
    return getCheckRiskMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RiskEngineServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RiskEngineServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RiskEngineServiceStub>() {
        @java.lang.Override
        public RiskEngineServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RiskEngineServiceStub(channel, callOptions);
        }
      };
    return RiskEngineServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RiskEngineServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RiskEngineServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RiskEngineServiceBlockingStub>() {
        @java.lang.Override
        public RiskEngineServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RiskEngineServiceBlockingStub(channel, callOptions);
        }
      };
    return RiskEngineServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RiskEngineServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RiskEngineServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RiskEngineServiceFutureStub>() {
        @java.lang.Override
        public RiskEngineServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RiskEngineServiceFutureStub(channel, callOptions);
        }
      };
    return RiskEngineServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void checkRisk(com.titan.riskengine.RiskCheckRequest request,
        io.grpc.stub.StreamObserver<com.titan.riskengine.RiskCheckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCheckRiskMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RiskEngineService.
   */
  public static abstract class RiskEngineServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RiskEngineServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RiskEngineService.
   */
  public static final class RiskEngineServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RiskEngineServiceStub> {
    private RiskEngineServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RiskEngineServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RiskEngineServiceStub(channel, callOptions);
    }

    /**
     */
    public void checkRisk(com.titan.riskengine.RiskCheckRequest request,
        io.grpc.stub.StreamObserver<com.titan.riskengine.RiskCheckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCheckRiskMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RiskEngineService.
   */
  public static final class RiskEngineServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RiskEngineServiceBlockingStub> {
    private RiskEngineServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RiskEngineServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RiskEngineServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.titan.riskengine.RiskCheckResponse checkRisk(com.titan.riskengine.RiskCheckRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCheckRiskMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RiskEngineService.
   */
  public static final class RiskEngineServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RiskEngineServiceFutureStub> {
    private RiskEngineServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RiskEngineServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RiskEngineServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.titan.riskengine.RiskCheckResponse> checkRisk(
        com.titan.riskengine.RiskCheckRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCheckRiskMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CHECK_RISK = 0;

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
        case METHODID_CHECK_RISK:
          serviceImpl.checkRisk((com.titan.riskengine.RiskCheckRequest) request,
              (io.grpc.stub.StreamObserver<com.titan.riskengine.RiskCheckResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      throw new AssertionError();
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getCheckRiskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.titan.riskengine.RiskCheckRequest,
              com.titan.riskengine.RiskCheckResponse>(
                service, METHODID_CHECK_RISK)))
        .build();
  }

  private static abstract class RiskEngineServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RiskEngineServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.titan.riskengine.RiskEngineProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RiskEngineService");
    }
  }

  private static final class RiskEngineServiceFileDescriptorSupplier
      extends RiskEngineServiceBaseDescriptorSupplier {
    RiskEngineServiceFileDescriptorSupplier() {}
  }

  private static final class RiskEngineServiceMethodDescriptorSupplier
      extends RiskEngineServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RiskEngineServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (RiskEngineServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RiskEngineServiceFileDescriptorSupplier())
              .addMethod(getCheckRiskMethod())
              .build();
        }
      }
    }
    return result;
  }
}
