### Hadoop RPC 框架使用抽象为如下步骤
+ 定义RPC协议
+ 实现RPC协议
+ 客户端获取代理对象
+ 服务器构造并启动RPC Server

#### 客户端获取Proxy对象


**DFSClient.java**

**FSClient** 持有 **ClientProtocol** 引用

```java
/** 代理对象 **/
final ClientProtocol namenode;

public DFSClient(URI nameNodeUri, ClientProtocol rpcNamenode,
      Configuration conf, FileSystem.Statistics stats)
    throws IOException {
    ...
    
    if (proxyInfo != null) {
      this.dtService = proxyInfo.getDelegationTokenService();
      // 构造方法
      this.namenode = proxyInfo.getProxy();
    } else if (rpcNamenode != null) {
      // This case is used for testing.
      Preconditions.checkArgument(nameNodeUri == null);
      this.namenode = rpcNamenode;
      dtService = null;
    } else {
      Preconditions.checkArgument(nameNodeUri != null,
          "null URI");
      // 核心方法，获取proxyInfo引用
      proxyInfo = NameNodeProxies.createProxy(conf, nameNodeUri,
          ClientProtocol.class, nnFallbackToSimpleAuth);
      this.dtService = proxyInfo.getDelegationTokenService();
      this.namenode = proxyInfo.getProxy();
    }
    
}

```


**NameNodeProxies.java**

```java

 public static <T> ProxyAndInfo<T> createProxy(Configuration conf,
      URI nameNodeUri, Class<T> xface, AtomicBoolean fallbackToSimpleAuth)
      throws IOException {
    AbstractNNFailoverProxyProvider<T> failoverProxyProvider =
        createFailoverProxyProvider(conf, nameNodeUri, xface, true,
          fallbackToSimpleAuth);
  
    if (failoverProxyProvider == null) {
      // Non-HA case 非ha模式
      // xface 是 rpc 接口 
      return createNonHAProxy(conf, NameNode.getAddress(nameNodeUri), xface,
          UserGroupInformation.getCurrentUser(), true, fallbackToSimpleAuth);
    } else {
      // HA case hdfs开启ha
      Conf config = new Conf(conf);
      // 直接调用动态代理构造方法，返回代理对象
      T proxy = (T) RetryProxy.create(xface, failoverProxyProvider,
          RetryPolicies.failoverOnNetworkException(
              RetryPolicies.TRY_ONCE_THEN_FAIL, config.maxFailoverAttempts,
              config.maxRetryAttempts, config.failoverSleepBaseMillis,
              config.failoverSleepMaxMillis));

      Text dtService;
      if (failoverProxyProvider.useLogicalURI()) {
        dtService = HAUtil.buildTokenServiceForLogicalUri(nameNodeUri,
            HdfsConstants.HDFS_URI_SCHEME);
      } else {
        dtService = SecurityUtil.buildTokenService(
            NameNode.getAddress(nameNodeUri));
      }
      return new ProxyAndInfo<T>(proxy, dtService,
          NameNode.getAddress(nameNodeUri));
    }
  }
  
  public static <T> ProxyAndInfo<T> createNonHAProxy(
      Configuration conf, InetSocketAddress nnAddr, Class<T> xface,
      UserGroupInformation ugi, boolean withRetries,
      AtomicBoolean fallbackToSimpleAuth) throws IOException {
    Text dtService = SecurityUtil.buildTokenService(nnAddr);
  
    T proxy;
    if (xface == ClientProtocol.class) {
      // 这里分析该接口
      proxy = (T) createNNProxyWithClientProtocol(nnAddr, conf, ugi,
          withRetries, fallbackToSimpleAuth);
    } else if (xface == JournalProtocol.class) {
      proxy = (T) createNNProxyWithJournalProtocol(nnAddr, conf, ugi);
    } else if (xface == NamenodeProtocol.class) {
      proxy = (T) createNNProxyWithNamenodeProtocol(nnAddr, conf, ugi,
          withRetries);
    } else if (xface == GetUserMappingsProtocol.class) {
      proxy = (T) createNNProxyWithGetUserMappingsProtocol(nnAddr, conf, ugi);
    } else if (xface == RefreshUserMappingsProtocol.class) {
      proxy = (T) createNNProxyWithRefreshUserMappingsProtocol(nnAddr, conf, ugi);
    } else if (xface == RefreshAuthorizationPolicyProtocol.class) {
      proxy = (T) createNNProxyWithRefreshAuthorizationPolicyProtocol(nnAddr,
          conf, ugi);
    } else if (xface == RefreshCallQueueProtocol.class) {
      proxy = (T) createNNProxyWithRefreshCallQueueProtocol(nnAddr, conf, ugi);
    } else {
      String message = "Unsupported protocol found when creating the proxy " +
          "connection to NameNode: " +
          ((xface != null) ? xface.getClass().getName() : "null");
      LOG.error(message);
      throw new IllegalStateException(message);
    }

    return new ProxyAndInfo<T>(proxy, dtService, nnAddr);
  }
  
  private static ClientProtocol createNNProxyWithClientProtocol(
      InetSocketAddress address, Configuration conf, UserGroupInformation ugi,
      boolean withRetries, AtomicBoolean fallbackToSimpleAuth)
      throws IOException {
    // 设置protocolEngine
    RPC.setProtocolEngine(conf, ClientNamenodeProtocolPB.class, ProtobufRpcEngine.class);

    ...
    
    // 构造ClientNamenodeProtocolPB代理对象, 此处分析getProtocolProxy()方法
    ClientNamenodeProtocolPB proxy = RPC.getProtocolProxy(
        ClientNamenodeProtocolPB.class, version, address, ugi, conf,
        NetUtils.getDefaultSocketFactory(conf),
        org.apache.hadoop.ipc.Client.getTimeout(conf), defaultPolicy,
        fallbackToSimpleAuth).getProxy();

    ...
    // 构造ClientNamenodeProtocolTranslatorPB代理对象
    return new ClientNamenodeProtocolTranslatorPB(proxy);
    
  }

```


**RPC.java**

```java

  // 返回对应的 RpcEngine 来处理对应的rpc协议 
  static synchronized RpcEngine getProtocolEngine(Class<?> protocol,
      Configuration conf) {
    // RpcEngine 是抽象类
    RpcEngine engine = PROTOCOL_ENGINES.get(protocol);
    
    // 通过反射来创建RpcEngine实例
    if (engine == null) {
      Class<?> impl = conf.getClass(ENGINE_PROP+"."+protocol.getName(),
                                    WritableRpcEngine.class);
      engine = (RpcEngine)ReflectionUtils.newInstance(impl, conf);
      PROTOCOL_ENGINES.put(protocol, engine);
    }
    return engine;
  }


  /**
   * 获取对接远程服务器节点的代理对象
   */
   public static <T> ProtocolProxy<T> getProtocolProxy(Class<T> protocol,
                                long clientVersion,
                                InetSocketAddress addr,
                                UserGroupInformation ticket,
                                Configuration conf,
                                SocketFactory factory,
                                int rpcTimeout,
                                RetryPolicy connectionRetryPolicy,
                                AtomicBoolean fallbackToSimpleAuth)
       throws IOException {
    if (UserGroupInformation.isSecurityEnabled()) {
      SaslRpcServer.init(conf);
    }
    
    // RpcEngine.getProxy()进行分流，由其子类 WritableRpcEngine 和 ProtobufRpcEngine 具体实现
    return getProtocolEngine(protocol, conf).getProxy(protocol, clientVersion,
        addr, ticket, conf, factory, rpcTimeout, connectionRetryPolicy,
        fallbackToSimpleAuth);
  }
  
```


**ProtobufRpcEngine.java**

```java

 private static class Invoker implements RpcInvocationHandler {
 
    /** 客户端侧rpc接口方法代理对象 */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws ServiceException {
      ...
      // 构造请求头，标明在什么接口调用什么方法
      RequestHeaderProto rpcRequestHeader = constructRpcRequestHeader(method);
      
      if (LOG.isTraceEnabled()) {
        LOG.trace(Thread.currentThread().getId() + ": Call -> " +
            remoteId + ": " + method.getName() +
            " {" + TextFormat.shortDebugString((Message) args[1]) + "}");
      }

      // 获取请求调用的参数
      Message theRequest = (Message) args[1];
      final RpcResponseWrapper val;
      try {
        // 调用RPC.Client发送请求
        val = (RpcResponseWrapper) client.call(RPC.RpcKind.RPC_PROTOCOL_BUFFER,
            new RpcRequestWrapper(rpcRequestHeader, theRequest), remoteId,
            fallbackToSimpleAuth);

      } catch (Throwable e) {
        if (LOG.isTraceEnabled()) {
          LOG.trace(Thread.currentThread().getId() + ": Exception <- " +
              remoteId + ": " + method.getName() +
                " {" + e + "}");
        }
        if (Trace.isTracing()) {
          traceScope.getSpan().addTimelineAnnotation(
              "Call got exception: " + e.getMessage());
        }
        throw new ServiceException(e);
      } finally {
        if (traceScope != null) traceScope.close();
      }

      if (LOG.isDebugEnabled()) {
        long callTime = Time.now() - startTime;
        LOG.debug("Call: " + method.getName() + " took " + callTime + "ms");
      }
      
      Message prototype = null;
      try {
        // 获取返回参数类型
        prototype = getReturnProtoType(method);
      } catch (Exception e) {
        throw new ServiceException(e);
      }
      Message returnMessage;
      try {
        // 序列化相应信息
        returnMessage = prototype.newBuilderForType()
            .mergeFrom(val.theResponseRead).build();

        ...

      } catch (Throwable e) {
        throw new ServiceException(e);
      }
      return returnMessage;
    }
 
 }

 public <T> ProtocolProxy<T> getProxy(Class<T> protocol, long clientVersion,
      InetSocketAddress addr, UserGroupInformation ticket, Configuration conf,
      SocketFactory factory, int rpcTimeout, RetryPolicy connectionRetryPolicy,
      AtomicBoolean fallbackToSimpleAuth) throws IOException {

    // 构造InvocationHandler对象
    final Invoker invoker = new Invoker(protocol, addr, ticket, conf, factory,
        rpcTimeout, connectionRetryPolicy, fallbackToSimpleAuth);
        
    // 调用Proxy.newProxyInstance()获取动态代理对象，并通过ProtocolProxy对象返回
    // Proxy.newProxyInstance()可参考Proxy源码
    return new ProtocolProxy<T>(protocol, (T) Proxy.newProxyInstance(
        protocol.getClassLoader(), new Class[]{protocol}, invoker), false);
  }
```


#### 服务器获取Server对象

##### 构造NameNodeRpcServer

**NameNodeRpcServer.java**
```java

 public NameNodeRpcServer(Configuration conf, NameNode nn)
      throws IOException {
    // ...
    // 设置RPC引擎为protobuf
    RPC.setProtocolEngine(conf, ClientNamenodeProtocolPB.class,
        ProtobufRpcEngine.class);

    // 构造ClientNamenodeProtocolServerSideTranslatorPB对象
    // 适配ClientProtocolPB到ClientProtocol接口的转换
    ClientNamenodeProtocolServerSideTranslatorPB clientProtocolServerTranslator = 
         new ClientNamenodeProtocolServerSideTranslatorPB(this);
         
     // 构造BlockingService对象
     // 用于将Server提取出的请求转到clientProtocolServerTranslator对象   
     BlockingService clientNNPbService = ClientNamenodeProtocol.
         newReflectiveBlockingService(clientProtocolServerTranslator);
    
    DatanodeProtocolServerSideTranslatorPB dnProtoPbTranslator = 
        new DatanodeProtocolServerSideTranslatorPB(this);
    BlockingService dnProtoPbService = DatanodeProtocolService
        .newReflectiveBlockingService(dnProtoPbTranslator);

    NamenodeProtocolServerSideTranslatorPB namenodeProtocolXlator = 
        new NamenodeProtocolServerSideTranslatorPB(this);
    BlockingService NNPbService = NamenodeProtocolService
          .newReflectiveBlockingService(namenodeProtocolXlator);
    
    // ... 其他接口同理
    
    WritableRpcEngine.ensureInitialized();

    // 初始化serviceRpcAddr对象，这个对象用于请求来自Datanode的请求
    InetSocketAddress serviceRpcAddr = nn.getServiceRpcServerAddress(conf);
    if (serviceRpcAddr != null) {
      String bindHost = nn.getServiceRpcServerBindHost(conf);
      if (bindHost == null) {
        bindHost = serviceRpcAddr.getHostName();
      }
      LOG.info("Service RPC server is binding to " + bindHost + ":" +
          serviceRpcAddr.getPort());

      int serviceHandlerCount =
        conf.getInt(DFS_NAMENODE_SERVICE_HANDLER_COUNT_KEY,
                    DFS_NAMENODE_SERVICE_HANDLER_COUNT_DEFAULT);
      // 构造serviceRpcServer对象，并配置ClientNamenodeProtocolPB的响应类为clientNNPbService
      this.serviceRpcServer = new RPC.Builder(conf)
          .setProtocol(
              org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolPB.class)
          .setInstance(clientNNPbService)
          .setBindAddress(bindHost)
          .setPort(serviceRpcAddr.getPort()).setNumHandlers(serviceHandlerCount)
          .setVerbose(false)
          .setSecretManager(namesystem.getDelegationTokenSecretManager())
          .build();

      // Add all the RPC protocols that the namenode implements
      // 注册NamenodePRCServer实现的所有接口
      DFSUtil.addPBProtocol(conf, HAServiceProtocolPB.class, haPbService,
          serviceRpcServer);
      DFSUtil.addPBProtocol(conf, NamenodeProtocolPB.class, NNPbService,
          serviceRpcServer);
      DFSUtil.addPBProtocol(conf, DatanodeProtocolPB.class, dnProtoPbService,
          serviceRpcServer);
      DFSUtil.addPBProtocol(conf, RefreshAuthorizationPolicyProtocolPB.class,
          refreshAuthService, serviceRpcServer);
      DFSUtil.addPBProtocol(conf, RefreshUserMappingsProtocolPB.class, 
          refreshUserMappingService, serviceRpcServer);
      // We support Refreshing call queue here in case the client RPC queue is full
      // 添加其他协议
      DFSUtil.addPBProtocol(conf, RefreshCallQueueProtocolPB.class,
          refreshCallQueueService, serviceRpcServer);
      DFSUtil.addPBProtocol(conf, GenericRefreshProtocolPB.class,
          genericRefreshService, serviceRpcServer);
      DFSUtil.addPBProtocol(conf, GetUserMappingsProtocolPB.class, 
          getUserMappingService, serviceRpcServer);
      DFSUtil.addPBProtocol(conf, TraceAdminProtocolPB.class,
          traceAdminService, serviceRpcServer);

      // Update the address with the correct port
      // 更新端口号
      InetSocketAddress listenAddr = serviceRpcServer.getListenerAddress();
      serviceRPCAddress = new InetSocketAddress(
            serviceRpcAddr.getHostName(), listenAddr.getPort());
      nn.setRpcServiceServerAddress(conf, serviceRPCAddress);
    } else {
      serviceRpcServer = null;
      serviceRPCAddress = null;
    }
    InetSocketAddress rpcAddr = nn.getRpcServerAddress(conf);
    String bindHost = nn.getRpcServerBindHost(conf);
    if (bindHost == null) {
      bindHost = rpcAddr.getHostName();
    }
    LOG.info("RPC server is binding to " + bindHost + ":" + rpcAddr.getPort());

    // 构造clientRpcServer，用于响应来自HDFS客户端的RPC请求
    this.clientRpcServer = new RPC.Builder(conf)
        .setProtocol(
            org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolPB.class)
        .setInstance(clientNNPbService).setBindAddress(bindHost)
        .setPort(rpcAddr.getPort()).setNumHandlers(handlerCount)
        .setVerbose(false)
        .setSecretManager(namesystem.getDelegationTokenSecretManager()).build();

    // Add all the RPC protocols that the namenode implements
    // 注册NamenodePRCServer实现的所有接口
    DFSUtil.addPBProtocol(conf, HAServiceProtocolPB.class, haPbService,
        clientRpcServer);
    DFSUtil.addPBProtocol(conf, NamenodeProtocolPB.class, NNPbService,
        clientRpcServer);
    DFSUtil.addPBProtocol(conf, DatanodeProtocolPB.class, dnProtoPbService,
        clientRpcServer);
    DFSUtil.addPBProtocol(conf, RefreshAuthorizationPolicyProtocolPB.class, 
        refreshAuthService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, RefreshUserMappingsProtocolPB.class, 
        refreshUserMappingService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, RefreshCallQueueProtocolPB.class,
        refreshCallQueueService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, GenericRefreshProtocolPB.class,
        genericRefreshService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, GetUserMappingsProtocolPB.class, 
        getUserMappingService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, TraceAdminProtocolPB.class,
        traceAdminService, clientRpcServer);

    // set service-level authorization security policy
    if (serviceAuthEnabled =
          conf.getBoolean(
            CommonConfigurationKeys.HADOOP_SECURITY_AUTHORIZATION, false)) {
      clientRpcServer.refreshServiceAcl(conf, new HDFSPolicyProvider());
      if (serviceRpcServer != null) {
        serviceRpcServer.refreshServiceAcl(conf, new HDFSPolicyProvider());
      }
    }

    // The rpc-server port can be ephemeral... ensure we have the correct info
    InetSocketAddress listenAddr = clientRpcServer.getListenerAddress();
      clientRpcAddress = new InetSocketAddress(
          rpcAddr.getHostName(), listenAddr.getPort());
    nn.setRpcServerAddress(conf, clientRpcAddress);
    
    minimumDataNodeVersion = conf.get(
        DFSConfigKeys.DFS_NAMENODE_MIN_SUPPORTED_DATANODE_VERSION_KEY,
        DFSConfigKeys.DFS_NAMENODE_MIN_SUPPORTED_DATANODE_VERSION_DEFAULT);

    // Set terse exception whose stack trace won't be logged
    this.clientRpcServer.addTerseExceptions(SafeModeException.class,
        FileNotFoundException.class,
        HadoopIllegalArgumentException.class,
        FileAlreadyExistsException.class,
        InvalidPathException.class,
        ParentNotDirectoryException.class,
        UnresolvedLinkException.class,
        AlreadyBeingCreatedException.class,
        QuotaExceededException.class,
        RecoveryInProgressException.class,
        AccessControlException.class,
        InvalidToken.class,
        LeaseExpiredException.class,
        NSQuotaExceededException.class,
        DSQuotaExceededException.class,
        AclException.class,
        FSLimitException.PathComponentTooLongException.class,
        FSLimitException.MaxDirectoryItemsExceededException.class,
        UnresolvedPathException.class);
 }
```

##### 获取BlockingService对象
```java
 public static com.google.protobuf.BlockingService
        newReflectiveBlockingService(final BlockingInterface impl) {
      return new com.google.protobuf.BlockingService() {
        public final com.google.protobuf.Descriptors.ServiceDescriptor
            getDescriptorForType() {
          return getDescriptor();
        }

        public final com.google.protobuf.Message callBlockingMethod(
            com.google.protobuf.Descriptors.MethodDescriptor method,
            com.google.protobuf.RpcController controller,
            com.google.protobuf.Message request)
            throws com.google.protobuf.ServiceException {
          if (method.getService() != getDescriptor()) {
            throw new java.lang.IllegalArgumentException(
              "Service.callBlockingMethod() given method descriptor for " +
              "wrong service type.");
          }
          switch(method.getIndex()) {
            case 0:
              return impl.getBlockLocations(controller, (org.apache.hadoop.hdfs.protocol.proto.ClientNamenodeProtocolProtos.GetBlockLocationsRequestProto)request);
            case 1:
              return impl.getServerDefaults(controller, (org.apache.hadoop.hdfs.protocol.proto.ClientNamenodeProtocolProtos.GetServerDefaultsRequestProto)request);
            case 2:
            // ...
            default:
            // ...
            
            }
```


##### 构造 Server 对象

**NameNodeRpcServer.java**

RPC.Server.build()方法构造Server对象

```java
  /** The RPC server that listens to requests from DataNodes */
  private final RPC.Server serviceRpcServer;
  private final InetSocketAddress serviceRPCAddress;
  
  /** The RPC server that listens to requests from clients */
  protected final RPC.Server clientRpcServer;
  protected final InetSocketAddress clientRpcAddress;

  this.serviceRpcServer = new RPC.Builder(conf)
          .setProtocol(
              org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolPB.class)
          .setInstance(clientNNPbService)
          .setBindAddress(bindHost)
          .setPort(serviceRpcAddr.getPort()).setNumHandlers(serviceHandlerCount)
          .setVerbose(false)
          .setSecretManager(namesystem.getDelegationTokenSecretManager())
          .build();
          
   this.clientRpcServer = new RPC.Builder(conf)
        .setProtocol(
            org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolPB.class)
        .setInstance(clientNNPbService).setBindAddress(bindHost)
        .setPort(rpcAddr.getPort()).setNumHandlers(handlerCount)
        .setVerbose(false)
        .setSecretManager(namesystem.getDelegationTokenSecretManager()).build();
```

**RPC.java**
```java
    /**
     * 构造RPC Server. 
     */
    public Server build() throws IOException, HadoopIllegalArgumentException {
      if (this.conf == null) {
        throw new HadoopIllegalArgumentException("conf is not set");
      }
      if (this.protocol == null) {
        throw new HadoopIllegalArgumentException("protocol is not set");
      }
      if (this.instance == null) {
        throw new HadoopIllegalArgumentException("instance is not set");
      }
      
      // 通过RpcEngine接口对象的getServer()方法进行分流，在其子类中具体实现
      return getProtocolEngine(this.protocol, this.conf).getServer(
          this.protocol, this.instance, this.bindAddress, this.port,
          this.numHandlers, this.numReaders, this.queueSizePerHandler,
          this.verbose, this.conf, this.secretManager, this.portRangeConfig);
    }
    
    
     // Register  protocol and its impl for rpc calls
    void registerProtocolAndImpl(RpcKind rpcKind, Class<?> protocolClass, 
       Object protocolImpl) {
           String protocolName = RPC.getProtocolName(protocolClass);
           long version;
     

           try {
             version = RPC.getProtocolVersion(protocolClass);
           } catch (Exception ex) {
             LOG.warn("Protocol "  + protocolClass + 
                  " NOT registered as cannot get protocol version ");
             return;
           }


           getProtocolImplMap(rpcKind).put(new ProtoNameVer(protocolName, version),
               new ProtoClassProtoImpl(protocolClass, protocolImpl)); 
           LOG.debug("RpcKind = " + rpcKind + " Protocol Name = " + protocolName +  " version=" + version +
               " ProtocolImpl=" + protocolImpl.getClass().getName() + 
               " protocolClass=" + protocolClass.getName());
   }
```

**ProtobufRpcEngine.java**
```java
 public RPC.Server getServer(Class<?> protocol, Object protocolImpl,
      String bindAddress, int port, int numHandlers, int numReaders,
      int queueSizePerHandler, boolean verbose, Configuration conf,
      SecretManager<? extends TokenIdentifier> secretManager,
      String portRangeConfig)
      throws IOException {
    return new Server(protocol, protocolImpl, conf, bindAddress, port,
        numHandlers, numReaders, queueSizePerHandler, verbose, secretManager,
        portRangeConfig);
  }
  
  // ...
  
  public static class Server extends RPC.Server {
  
    public Server(Class<?> protocolClass, Object protocolImpl,
        Configuration conf, String bindAddress, int port, int numHandlers,
        int numReaders, int queueSizePerHandler, boolean verbose,
        SecretManager<? extends TokenIdentifier> secretManager, 
        String portRangeConfig)
        throws IOException {
      super(bindAddress, port, null, numHandlers,
          numReaders, queueSizePerHandler, conf, classNameBase(protocolImpl
              .getClass().getName()), secretManager, portRangeConfig);
      this.verbose = verbose;  
      
      // 父类RPC.Server中的方法
      registerProtocolAndImpl(RPC.RpcKind.RPC_PROTOCOL_BUFFER, protocolClass,
          protocolImpl);
    }
    
    // ...
    
    /** ProtoBufRpcInvoker 是 ProtobufRpcEngine.Server类最重要的实现 */
    static class ProtoBufRpcInvoker implements RpcInvoker {
    
      private static ProtoClassProtoImpl getProtocolImpl(RPC.Server server,
          String protoName, long clientVersion) throws RpcServerException {
        ProtoNameVer pv = new ProtoNameVer(protoName, clientVersion);
        
        // 从RPC.Server的ProtocolImplMap对象中获取接口信息对应的实现类
        ProtoClassProtoImpl impl = server.getProtocolImplMap(RPC.RpcKind.RPC_PROTOCOL_BUFFER).get(pv);
        if (impl == null) { // no match for Protocol AND Version
          VerProtocolImpl highest = 
              server.getHighestSupportedProtocol(RPC.RpcKind.RPC_PROTOCOL_BUFFER, 
                  protoName);
                 
          // 如果不存在实现类，则抛出异常
          if (highest == null) {
            throw new RpcNoSuchProtocolException(
                "Unknown protocol: " + protoName);
          }
          // 如果RPC版本不匹配，则抛出异常
          throw new RPC.VersionMismatch(protoName, clientVersion,
              highest.version);
        }
        
        // 返回实现类
        return impl;
      }

      // ProtoBufRpcInvoker.call()方法会响应RPC.Server类解析出网络的RPC请求
      public Writable call(RPC.Server server, String protocol,
          Writable writableRequest, long receiveTime) throws Exception {
        
        // 获取rpc调用头 
        RpcRequestWrapper request = (RpcRequestWrapper) writableRequest;
        RequestHeaderProto rpcRequest = request.requestHeader;
        
        // 获取调用的接口名，方法名，版本号
        String methodName = rpcRequest.getMethodName();
        String protoName = rpcRequest.getDeclaringClassProtocolName();
        long clientVersion = rpcRequest.getClientProtocolVersion();
        if (server.verbose)
          LOG.info("Call: protocol=" + protocol + ", method=" + methodName);
        
        // 获取该接口在Server侧对应的实现类
        ProtoClassProtoImpl protocolImpl = getProtocolImpl(server, protoName,
            clientVersion);
        BlockingService service = (BlockingService) protocolImpl.protocolImpl;
        MethodDescriptor methodDescriptor = service.getDescriptorForType()
            .findMethodByName(methodName);
        if (methodDescriptor == null) {
          String msg = "Unknown method " + methodName + " called on " + protocol
              + " protocol.";
          LOG.warn(msg);
          throw new RpcNoSuchMethodException(msg);
        }
        
        // 获取调用的方法描述符以及调用参数
        Message prototype = service.getRequestPrototype(methodDescriptor);
        Message param = prototype.newBuilderForType()
            .mergeFrom(request.theRequestRead).build();
        
        Message result;
        long startTime = Time.now();
        int qTime = (int) (startTime - receiveTime);
        Exception exception = null;
        try {
          server.rpcDetailedMetrics.init(protocolImpl.protocolClass);
          
          // 在实现类上调用callBlockingMethod方法，级联适配调用到NameNodeRpcServer
          result = service.callBlockingMethod(methodDescriptor, null, param);
        } catch (ServiceException e) {
          exception = (Exception) e.getCause();
          throw (Exception) e.getCause();
        } catch (Exception e) {
          exception = e;
          throw e;
        } finally {
          int processingTime = (int) (Time.now() - startTime);
          if (LOG.isDebugEnabled()) {
            String msg = "Served: " + methodName + " queueTime= " + qTime +
                " procesingTime= " + processingTime;
            if (exception != null) {
              msg += " exception= " + exception.getClass().getSimpleName();
            }
            LOG.debug(msg);
          }
          String detailedMetricsName = (exception == null) ?
              methodName :
              exception.getClass().getSimpleName();
          server.rpcMetrics.addRpcQueueTime(qTime);
          server.rpcMetrics.addRpcProcessingTime(processingTime);
          server.rpcDetailedMetrics.addProcessingTime(detailedMetricsName,
              processingTime);
        }
        return new RpcResponseWrapper(result);
      }
    }
  }
```


**RpcEngine.java**

```java
public interface RpcEngine {

  /** 构造一个客户端侧的代理对象 */
  <T> ProtocolProxy<T> getProxy(Class<T> protocol,
                  long clientVersion, InetSocketAddress addr,
                  UserGroupInformation ticket, Configuration conf,
                  SocketFactory factory, int rpcTimeout,
                  RetryPolicy connectionRetryPolicy) throws IOException;

  <T> ProtocolProxy<T> getProxy(Class<T> protocol,
                  long clientVersion, InetSocketAddress addr,
                  UserGroupInformation ticket, Configuration conf,
                  SocketFactory factory, int rpcTimeout,
                  RetryPolicy connectionRetryPolicy,
                  AtomicBoolean fallbackToSimpleAuth) throws IOException;

  /** 构造一个服务端侧的实现类 */
  
  RPC.Server getServer(Class<?> protocol, Object instance, String bindAddress,
                       int port, int numHandlers, int numReaders,
                       int queueSizePerHandler, boolean verbose,
                       Configuration conf, 
                       SecretManager<? extends TokenIdentifier> secretManager,
                       String portRangeConfig
                       ) throws IOException;

  ProtocolProxy<ProtocolMetaInfoPB> getProtocolMetaInfoProxy(
      ConnectionId connId, Configuration conf, SocketFactory factory)
      throws IOException;
}
```
