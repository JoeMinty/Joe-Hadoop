## RPC类

## Client类

  Client类只有一个入口，`call()`方法。代理类调用此方法将rpc请求发送到远程服务器，等待相应。
  
  - 将rpc请求封装成Call对象
  - 创建Connection对象，用于指向server与client的socket连接
  - `Connection.setupIostreams()`
  - `Connection.sendRprRequest()`
  - `Call.wait()`
  - `call.notify()`唤醒调用call方法的线程读取Call对象的返回值
  

```java
 /**
   * 将rpc请求封装成Call对象，通过代理对象发送给服务器，返回响应
   *
   * @param rpcKind
   * @param rpcRequest -  序列化的方法和参数
   * @param remoteId - 目的远程服务器
   * @param serviceClass - RPC服务的类
   * @param fallbackToSimpleAuth - 一个安全的客户端返回给简单认证
   */
  public Writable call(RPC.RpcKind rpcKind, Writable rpcRequest,
      ConnectionId remoteId, int serviceClass,
      AtomicBoolean fallbackToSimpleAuth) throws IOException {
      
    // 构造call对象 
    final Call call = createCall(rpcKind, rpcRequest);
    
    // 构造Connection对象
    Connection connection = getConnection(remoteId, call, serviceClass, fallbackToSimpleAuth);
    try {
      connection.sendRpcRequest(call);                 // 发送rpc请求
    } catch (RejectedExecutionException e) {
      throw new IOException("connection has been closed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("interrupted waiting to send rpc request to server", e);
      throw new IOException(e);
    }

    synchronized (call) {
      while (!call.done) {
        try {
          call.wait();                           // 等待rpc响应
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("Call interrupted");
        }
      }

      // 如果发送线程被唤醒，但是服务器处理rpc请求时出现异常
      if (call.error != null) {
        // 从Call对象中获取异常，并抛出
        if (call.error instanceof RemoteException) {
          call.error.fillInStackTrace();
          throw call.error;
        } else { // local exception
          InetSocketAddress address = connection.getRemoteAddress();
          throw NetUtils.wrapException(address.getHostName(),
                  address.getPort(),
                  NetUtils.getHostname(),
                  0,
                  call.error);
        }
      } else {
        // 服务器成功发回响应信息，返回RPC响应
        return call.getRpcResponse();
      }
    }
  }
```

### 内部类--Call
`RPC.Client`中发送请求和接受响应是由两个独立的线程继续的。
发送请求 -- `Client.call()`
接受响应 -- `call()`启动的`Connection`线程

### 内部类--Connection
`Connection`是一个线程类，提供了建立 Client 到 Server 的 Socket 连接，发送 RPC 请求以及读取 RPC 响应等功能。

```java

 private Hashtable<ConnectionId, Connection> connections = new Hashtable<ConnectionId, Connection>();
 
 /**
  * 从缓存中获取一个连接，如果缓存中没有，则新建一个并将其加入到缓存中
  */
 private Connection getConnection(ConnectionId remoteId,
      Call call, int serviceClass, AtomicBoolean fallbackToSimpleAuth)
      throws IOException {
    if (!running.get()) {
      // the client is stopped
      throw new IOException("The client is stopped");
    }
    Connection connection;
    
    do {
      synchronized (connections) {
        // 首先尝试从Client.connections队列中获取Connection对象
        connection = connections.get(remoteId);
        if (connection == null) {
          // 如果connections队列中没有保存，则构造新的对象
          connection = new Connection(remoteId, serviceClass);
          connections.put(remoteId, connection);
        }
      }
    // 将待发送请求的Call对象放入Connection对象并获取IO流
    } while (!connection.addCall(call));

    // 我们并没有将rpc请求调用的方法在上面的同步块中执行，因为如果server反应比较慢，那么建立连接会比较耗时
    // 调用setupIOstreams()方法，初始化Connection对象并获取IO流
    connection.setupIOstreams(fallbackToSimpleAuth);
    return connection;
  }
 ```

**Connection构造方法**
```java
 public Connection(ConnectionId remoteId, int serviceClass)
```
设为守护线程

**setupIOstreams**
- 建立与远程服务的`Socket`连接
- 向服务器发送连接头
- 启动Connection线程监听Socket输入流并等待服务器返回RPC响应

```java
    /** 
     * 连接server并且设置io流
     * 发送连接头，启动线程监听响应
     */
    private synchronized void setupIOstreams(
        AtomicBoolean fallbackToSimpleAuth) {
      if (socket != null || shouldCloseConnection.get()) {
        return;
      } 
      try {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Connecting to "+server);
        }
        if (Trace.isTracing()) {
          Trace.addTimelineAnnotation("IPC client connecting to " + server);
        }
        short numRetries = 0;
        Random rand = null;
        while (true) {
          // 1.建立到远程服务器的连接
          setupConnection();
          InputStream inStream = NetUtils.getInputStream(socket);
          OutputStream outStream = NetUtils.getOutputStream(socket);
          
          // 2.发送连接头域
          writeConnectionHeader(outStream);
          if (authProtocol == AuthProtocol.SASL) {
            final InputStream in2 = inStream;
            final OutputStream out2 = outStream;
            UserGroupInformation ticket = remoteId.getTicket();
            if (ticket.getRealUser() != null) {
              ticket = ticket.getRealUser();
            }
            try {
              authMethod = ticket
                  .doAs(new PrivilegedExceptionAction<AuthMethod>() {
                    @Override
                    public AuthMethod run()
                        throws IOException, InterruptedException {
                      return setupSaslConnection(in2, out2);
                    }
                  });
            } catch (Exception ex) {
              authMethod = saslRpcClient.getAuthMethod();
              if (rand == null) {
                rand = new Random();
              }
              handleSaslConnectionFailure(numRetries++, maxRetriesOnSasl, ex,
                  rand, ticket);
              continue;
            }
            if (authMethod != AuthMethod.SIMPLE) {
              // Sasl connect is successful. Let's set up Sasl i/o streams.
              inStream = saslRpcClient.getInputStream(inStream);
              outStream = saslRpcClient.getOutputStream(outStream);
              // for testing
              remoteId.saslQop =
                  (String)saslRpcClient.getNegotiatedProperty(Sasl.QOP);
              LOG.debug("Negotiated QOP is :" + remoteId.saslQop);
              if (fallbackToSimpleAuth != null) {
                fallbackToSimpleAuth.set(false);
              }
            } else if (UserGroupInformation.isSecurityEnabled()) {
              if (!fallbackAllowed) {
                throw new IOException("Server asks us to fall back to SIMPLE " +
                    "auth, but this client is configured to only allow secure " +
                    "connections.");
              }
              if (fallbackToSimpleAuth != null) {
                fallbackToSimpleAuth.set(true);
              }
            }
          }
        
          // 3.包装输入输出流
          if (doPing) {
            inStream = new PingInputStream(inStream);
          }
          this.in = new DataInputStream(new BufferedInputStream(inStream));

          // SASL may have already buffered the stream
          if (!(outStream instanceof BufferedOutputStream)) {
            outStream = new BufferedOutputStream(outStream);
          }
          this.out = new DataOutputStream(outStream);
          
          // 4.写入连接上下文头域
          writeConnectionContext(remoteId, authMethod);

          // update last activity time
          // 5.更新上次活跃时间
          touch();

          if (Trace.isTracing()) {
            Trace.addTimelineAnnotation("IPC client connected to " + server);
          }

          // start the receiver thread after the socket connection has been set
          // up
          // 启动Connection线程
          start();
          return;
        }
      } catch (Throwable t) {
        if (t instanceof IOException) {
          markClosed((IOException)t);
        } else {
          markClosed(new IOException("Couldn't set up IO streams", t));
        }
        close();
      }
    }
```


## Server类
