package com.titan.promotions.escrow;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.60.0)",
    comments = "Source: escrow.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class EscrowServiceGrpc {

  private EscrowServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "titan.escrow.EscrowService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.titan.promotions.escrow.EscrowProto.LockFundsRequest,
      com.titan.promotions.escrow.EscrowProto.LockFundsResponse> getLockFundsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "LockFunds",
      requestType = com.titan.promotions.escrow.EscrowProto.LockFundsRequest.class,
      responseType = com.titan.promotions.escrow.EscrowProto.LockFundsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.titan.promotions.escrow.EscrowProto.LockFundsRequest,
      com.titan.promotions.escrow.EscrowProto.LockFundsResponse> getLockFundsMethod() {
    io.grpc.MethodDescriptor<com.titan.promotions.escrow.EscrowProto.LockFundsRequest, com.titan.promotions.escrow.EscrowProto.LockFundsResponse> getLockFundsMethod;
    if ((getLockFundsMethod = EscrowServiceGrpc.getLockFundsMethod) == null) {
      synchronized (EscrowServiceGrpc.class) {
        if ((getLockFundsMethod = EscrowServiceGrpc.getLockFundsMethod) == null) {
          EscrowServiceGrpc.getLockFundsMethod = getLockFundsMethod =
              io.grpc.MethodDescriptor.<com.titan.promotions.escrow.EscrowProto.LockFundsRequest, com.titan.promotions.escrow.EscrowProto.LockFundsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "LockFunds"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.promotions.escrow.EscrowProto.LockFundsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.promotions.escrow.EscrowProto.LockFundsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EscrowServiceMethodDescriptorSupplier("LockFunds"))
              .build();
        }
      }
    }
    return getLockFundsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest,
      com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse> getReleaseFundsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReleaseFunds",
      requestType = com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest.class,
      responseType = com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest,
      com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse> getReleaseFundsMethod() {
    io.grpc.MethodDescriptor<com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest, com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse> getReleaseFundsMethod;
    if ((getReleaseFundsMethod = EscrowServiceGrpc.getReleaseFundsMethod) == null) {
      synchronized (EscrowServiceGrpc.class) {
        if ((getReleaseFundsMethod = EscrowServiceGrpc.getReleaseFundsMethod) == null) {
          EscrowServiceGrpc.getReleaseFundsMethod = getReleaseFundsMethod =
              io.grpc.MethodDescriptor.<com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest, com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReleaseFunds"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EscrowServiceMethodDescriptorSupplier("ReleaseFunds"))
              .build();
        }
      }
    }
    return getReleaseFundsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest,
      com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse> getCheckBalanceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CheckBalance",
      requestType = com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest.class,
      responseType = com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest,
      com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse> getCheckBalanceMethod() {
    io.grpc.MethodDescriptor<com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest, com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse> getCheckBalanceMethod;
    if ((getCheckBalanceMethod = EscrowServiceGrpc.getCheckBalanceMethod) == null) {
      synchronized (EscrowServiceGrpc.class) {
        if ((getCheckBalanceMethod = EscrowServiceGrpc.getCheckBalanceMethod) == null) {
          EscrowServiceGrpc.getCheckBalanceMethod = getCheckBalanceMethod =
              io.grpc.MethodDescriptor.<com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest, com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CheckBalance"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EscrowServiceMethodDescriptorSupplier("CheckBalance"))
              .build();
        }
      }
    }
    return getCheckBalanceMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EscrowServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EscrowServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EscrowServiceStub>() {
        @java.lang.Override
        public EscrowServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EscrowServiceStub(channel, callOptions);
        }
      };
    return EscrowServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EscrowServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EscrowServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EscrowServiceBlockingStub>() {
        @java.lang.Override
        public EscrowServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EscrowServiceBlockingStub(channel, callOptions);
        }
      };
    return EscrowServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EscrowServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EscrowServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EscrowServiceFutureStub>() {
        @java.lang.Override
        public EscrowServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EscrowServiceFutureStub(channel, callOptions);
        }
      };
    return EscrowServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void lockFunds(com.titan.promotions.escrow.EscrowProto.LockFundsRequest request,
        io.grpc.stub.StreamObserver<com.titan.promotions.escrow.EscrowProto.LockFundsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getLockFundsMethod(), responseObserver);
    }

    /**
     */
    default void releaseFunds(com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest request,
        io.grpc.stub.StreamObserver<com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReleaseFundsMethod(), responseObserver);
    }

    /**
     */
    default void checkBalance(com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest request,
        io.grpc.stub.StreamObserver<com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCheckBalanceMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service EscrowService.
   */
  public static abstract class EscrowServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return EscrowServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service EscrowService.
   */
  public static final class EscrowServiceStub
      extends io.grpc.stub.AbstractAsyncStub<EscrowServiceStub> {
    private EscrowServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EscrowServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EscrowServiceStub(channel, callOptions);
    }

    /**
     */
    public void lockFunds(com.titan.promotions.escrow.EscrowProto.LockFundsRequest request,
        io.grpc.stub.StreamObserver<com.titan.promotions.escrow.EscrowProto.LockFundsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getLockFundsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void releaseFunds(com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest request,
        io.grpc.stub.StreamObserver<com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReleaseFundsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void checkBalance(com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest request,
        io.grpc.stub.StreamObserver<com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCheckBalanceMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service EscrowService.
   */
  public static final class EscrowServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<EscrowServiceBlockingStub> {
    private EscrowServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EscrowServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EscrowServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.titan.promotions.escrow.EscrowProto.LockFundsResponse lockFunds(com.titan.promotions.escrow.EscrowProto.LockFundsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getLockFundsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse releaseFunds(com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReleaseFundsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse checkBalance(com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCheckBalanceMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service EscrowService.
   */
  public static final class EscrowServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<EscrowServiceFutureStub> {
    private EscrowServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EscrowServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EscrowServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.titan.promotions.escrow.EscrowProto.LockFundsResponse> lockFunds(
        com.titan.promotions.escrow.EscrowProto.LockFundsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getLockFundsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse> releaseFunds(
        com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReleaseFundsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse> checkBalance(
        com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCheckBalanceMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LOCK_FUNDS = 0;
  private static final int METHODID_RELEASE_FUNDS = 1;
  private static final int METHODID_CHECK_BALANCE = 2;

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
        case METHODID_LOCK_FUNDS:
          serviceImpl.lockFunds((com.titan.promotions.escrow.EscrowProto.LockFundsRequest) request,
              (io.grpc.stub.StreamObserver<com.titan.promotions.escrow.EscrowProto.LockFundsResponse>) responseObserver);
          break;
        case METHODID_RELEASE_FUNDS:
          serviceImpl.releaseFunds((com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest) request,
              (io.grpc.stub.StreamObserver<com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse>) responseObserver);
          break;
        case METHODID_CHECK_BALANCE:
          serviceImpl.checkBalance((com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest) request,
              (io.grpc.stub.StreamObserver<com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse>) responseObserver);
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
          getLockFundsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.titan.promotions.escrow.EscrowProto.LockFundsRequest,
              com.titan.promotions.escrow.EscrowProto.LockFundsResponse>(
                service, METHODID_LOCK_FUNDS)))
        .addMethod(
          getReleaseFundsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.titan.promotions.escrow.EscrowProto.ReleaseFundsRequest,
              com.titan.promotions.escrow.EscrowProto.ReleaseFundsResponse>(
                service, METHODID_RELEASE_FUNDS)))
        .addMethod(
          getCheckBalanceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.titan.promotions.escrow.EscrowProto.CheckBalanceRequest,
              com.titan.promotions.escrow.EscrowProto.CheckBalanceResponse>(
                service, METHODID_CHECK_BALANCE)))
        .build();
  }

  private static abstract class EscrowServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EscrowServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.titan.promotions.escrow.EscrowProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("EscrowService");
    }
  }

  private static final class EscrowServiceFileDescriptorSupplier
      extends EscrowServiceBaseDescriptorSupplier {
    EscrowServiceFileDescriptorSupplier() {}
  }

  private static final class EscrowServiceMethodDescriptorSupplier
      extends EscrowServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    EscrowServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (EscrowServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EscrowServiceFileDescriptorSupplier())
              .addMethod(getLockFundsMethod())
              .addMethod(getReleaseFundsMethod())
              .addMethod(getCheckBalanceMethod())
              .build();
        }
      }
    }
    return result;
  }
}
