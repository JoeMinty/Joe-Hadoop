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

### 内部类--Connection

## Server类
