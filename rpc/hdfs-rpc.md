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
