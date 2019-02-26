
/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.datanode;


import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_ADDRESS_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_ADDRESS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DATA_DIR_PERMISSION_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DATA_DIR_PERMISSION_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DIRECTORYSCAN_INTERVAL_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DIRECTORYSCAN_INTERVAL_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DNS_INTERFACE_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DNS_INTERFACE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DNS_NAMESERVER_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DNS_NAMESERVER_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_HANDLER_COUNT_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_HANDLER_COUNT_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_HOST_NAME_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_HTTP_ADDRESS_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_IPC_ADDRESS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_KERBEROS_PRINCIPAL_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_KEYTAB_FILE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_MAX_LOCKED_MEMORY_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_NETWORK_COUNTS_CACHE_MAX_SIZE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_NETWORK_COUNTS_CACHE_MAX_SIZE_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_PLUGINS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_SCAN_PERIOD_HOURS_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_SCAN_PERIOD_HOURS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_STARTUP_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_MAX_NUM_BLOCKS_TO_LOG_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_MAX_NUM_BLOCKS_TO_LOG_KEY;
import static org.apache.hadoop.util.ExitUtil.terminate;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.ObjectName;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.ReconfigurableBase;
import org.apache.hadoop.conf.ReconfigurationException;
import org.apache.hadoop.conf.ReconfigurationTaskStatus;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.client.BlockReportOptions;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.DFSUtil.ConfiguredNNAddress;
import org.apache.hadoop.hdfs.HDFSPolicyProvider;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.net.DomainPeerServer;
import org.apache.hadoop.hdfs.net.TcpPeerServer;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockLocalPathInfo;
import org.apache.hadoop.hdfs.protocol.ClientDatanodeProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.DatanodeLocalInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsBlocksMetadata;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.RecoveryInProgressException;
import org.apache.hadoop.hdfs.protocol.datatransfer.BlockConstructionStage;
import org.apache.hadoop.hdfs.protocol.datatransfer.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.datatransfer.IOStreamPair;
import org.apache.hadoop.hdfs.protocol.datatransfer.PipelineAck;
import org.apache.hadoop.hdfs.protocol.datatransfer.Sender;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.DataEncryptionKeyFactory;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.SaslDataTransferClient;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.SaslDataTransferServer;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.ClientDatanodeProtocolService;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.DNTransferAckProto;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.Status;
import org.apache.hadoop.hdfs.protocol.proto.InterDatanodeProtocolProtos.InterDatanodeProtocolService;
import org.apache.hadoop.hdfs.protocolPB.ClientDatanodeProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.ClientDatanodeProtocolServerSideTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.InterDatanodeProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.InterDatanodeProtocolServerSideTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.InterDatanodeProtocolTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.PBHelper;
import org.apache.hadoop.hdfs.security.token.block.BlockPoolTokenSecretManager;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenSecretManager;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenSecretManager.AccessMode;
import org.apache.hadoop.hdfs.security.token.block.DataEncryptionKey;
import org.apache.hadoop.hdfs.security.token.block.ExportedBlockKeys;
import org.apache.hadoop.hdfs.security.token.block.InvalidBlockTokenException;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.NodeType;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.ReplicaState;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.common.StorageInfo;
import org.apache.hadoop.hdfs.server.datanode.SecureDataNodeStarter.SecureResources;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsDatasetSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.datanode.metrics.DataNodeMetrics;
import org.apache.hadoop.hdfs.server.datanode.web.DatanodeHttpServer;
import org.apache.hadoop.hdfs.server.protocol.BlockRecoveryCommand.RecoveringBlock;
import org.apache.hadoop.hdfs.server.protocol.DatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.InterDatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.ReplicaRecoveryInfo;
import org.apache.hadoop.http.HttpConfig;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.ReadaheadPool;
import org.apache.hadoop.io.nativeio.NativeIO;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.metrics2.util.MBeans;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.unix.DomainSocket;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.SaslPropertiesResolver;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.tracing.TraceAdminPB.TraceAdminService;
import org.apache.hadoop.tracing.TraceAdminProtocolPB;
import org.apache.hadoop.tracing.TraceAdminProtocolServerSideTranslatorPB;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.DiskChecker;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.JvmPauseMonitor;
import org.apache.hadoop.util.ServicePlugin;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.util.VersionInfo;
import org.apache.hadoop.tracing.SpanReceiverHost;
import org.apache.hadoop.tracing.SpanReceiverInfo;
import org.apache.hadoop.tracing.TraceAdminProtocol;
import org.mortbay.util.ajax.JSON;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.protobuf.BlockingService;

/**********************************************************
 * DataNode is a class (and program) that stores a set of
 * blocks for a DFS deployment.  A single deployment can
 * have one or many DataNodes.  Each DataNode communicates
 * regularly with a single NameNode.  It also communicates
 * with client code and other DataNodes from time to time.
 *
 * DataNodes store a series of named blocks.  The DataNode
 * allows client code to read these blocks, or to write new
 * block data.  The DataNode may also, in response to instructions
 * from its NameNode, delete blocks or copy blocks to/from other
 * DataNodes.
 *
 * The DataNode maintains just one critical table:
 *   block-> stream of bytes (of BLOCK_SIZE or less)
 *
 * This info is stored on a local disk.  The DataNode
 * reports the table's contents to the NameNode upon startup
 * and every so often afterwards.
 *
 * DataNodes spend their lives in an endless loop of asking
 * the NameNode for something to do.  A NameNode cannot connect
 * to a DataNode directly; a NameNode simply returns values from
 * functions invoked by a DataNode.
 *
 * DataNodes maintain an open server socket so that client code 
 * or other DataNodes can read/write data.  The host/port for
 * this server is reported to the NameNode, which then sends that
 * information to clients or other DataNodes that might be interested.
 *
 **********************************************************/
@InterfaceAudience.Private
public class DataNode extends ReconfigurableBase
    implements InterDatanodeProtocol, ClientDatanodeProtocol,
        TraceAdminProtocol, DataNodeMXBean {
  public static final Log LOG = LogFactory.getLog(DataNode.class);
  
  static{
    HdfsConfiguration.init();
  }

  public static final String DN_CLIENTTRACE_FORMAT =
        "src: %s" +      // src IP
        ", dest: %s" +   // dst IP
        ", bytes: %s" +  // byte count
        ", op: %s" +     // operation
        ", cliID: %s" +  // DFSClient id
        ", offset: %s" + // offset
        ", srvID: %s" +  // DatanodeRegistration
        ", blockid: %s" + // block id
        ", duration: %s";  // duration time
        
  static final Log ClientTraceLog =
    LogFactory.getLog(DataNode.class.getName() + ".clienttrace");
  
  private static final String USAGE =
      "Usage: java DataNode [-regular | -rollback]\n" +
      "    -regular                 : Normal DataNode startup (default).\n" +
      "    -rollback                : Rollback a standard or rolling upgrade.\n" +
      "  Refer to HDFS documentation for the difference between standard\n" +
      "  and rolling upgrades.";

  static final int CURRENT_BLOCK_FORMAT_VERSION = 1;

  /**
   * Use {@link NetUtils#createSocketAddr(String)} instead.
   */
  @Deprecated
  public static InetSocketAddress createSocketAddr(String target) {
    return NetUtils.createSocketAddr(target);
  }
  
  volatile boolean shouldRun = true;
  volatile boolean shutdownForUpgrade = false;
  private boolean shutdownInProgress = false;
  private BlockPoolManager blockPoolManager;
  volatile FsDatasetSpi<? extends FsVolumeSpi> data = null;
  private String clusterId = null;

  public final static String EMPTY_DEL_HINT = "";
  final AtomicInteger xmitsInProgress = new AtomicInteger();
  Daemon dataXceiverServer = null;
  DataXceiverServer xserver = null;
  Daemon localDataXceiverServer = null;
  ShortCircuitRegistry shortCircuitRegistry = null;
  ThreadGroup threadGroup = null;
  private DNConf dnConf;
  private volatile boolean heartbeatsDisabledForTests = false;
  private DataStorage storage = null;

  private DatanodeHttpServer httpServer = null;
  private int infoPort;
  private int infoSecurePort;

  DataNodeMetrics metrics;
  private InetSocketAddress streamingAddr;
  
  // See the note below in incrDatanodeNetworkErrors re: concurrency.
  private LoadingCache<String, Map<String, Long>> datanodeNetworkCounts;

  private String hostName;
  private DatanodeID id;
  
  final private String fileDescriptorPassingDisabledReason;
  boolean isBlockTokenEnabled;
  BlockPoolTokenSecretManager blockPoolTokenSecretManager;
  private boolean hasAnyBlockPoolRegistered = false;
  
  private final BlockScanner blockScanner;
  private DirectoryScanner directoryScanner = null;
  
  /** Activated plug-ins. */
  private List<ServicePlugin> plugins;
  
  // For InterDataNodeProtocol
  public RPC.Server ipcServer;

  private JvmPauseMonitor pauseMonitor;

  private SecureResources secureResources = null;
  // dataDirs must be accessed while holding the DataNode lock.
  private List<StorageLocation> dataDirs;
  private Configuration conf;
  private final String confVersion;
  private final long maxNumberOfBlocksToLog;
  private final boolean pipelineSupportECN;

  private final List<String> usersWithLocalPathAccess;
  private final boolean connectToDnViaHostname;
  ReadaheadPool readaheadPool;
  SaslDataTransferClient saslClient;
  SaslDataTransferServer saslServer;
  private final boolean getHdfsBlockLocationsEnabled;
  private ObjectName dataNodeInfoBeanName;
  private Thread checkDiskErrorThread = null;
  protected final int checkDiskErrorInterval = 5*1000;
  private boolean checkDiskErrorFlag = false;
  private Object checkDiskErrorMutex = new Object();
  private long lastDiskErrorCheck;
  private String supergroup;
  private boolean isPermissionEnabled;
  private String dnUserName = null;

  private SpanReceiverHost spanReceiverHost;

  /**
   * Creates a dummy DataNode for testing purpose.
   */
  @VisibleForTesting
  @InterfaceAudience.LimitedPrivate("HDFS")
  DataNode(final Configuration conf) {
    super(conf);
    this.blockScanner = new BlockScanner(this, conf);
    this.fileDescriptorPassingDisabledReason = null;
    this.maxNumberOfBlocksToLog = 0;
    this.confVersion = null;
    this.usersWithLocalPathAccess = null;
    this.connectToDnViaHostname = false;
    this.getHdfsBlockLocationsEnabled = false;
    this.pipelineSupportECN = false;
  }

  /**
   * Create the DataNode given a configuration, an array of dataDirs,
   * and a namenode proxy
   */
  DataNode(final Configuration conf,
           final List<StorageLocation> dataDirs,
           final SecureResources resources) throws IOException {
    super(conf);
    this.blockScanner = new BlockScanner(this, conf);
    this.lastDiskErrorCheck = 0;
    this.maxNumberOfBlocksToLog = conf.getLong(DFS_MAX_NUM_BLOCKS_TO_LOG_KEY,
        DFS_MAX_NUM_BLOCKS_TO_LOG_DEFAULT);

    this.usersWithLocalPathAccess = Arrays.asList(
        conf.getTrimmedStrings(DFSConfigKeys.DFS_BLOCK_LOCAL_PATH_ACCESS_USER_KEY));
    this.connectToDnViaHostname = conf.getBoolean(
        DFSConfigKeys.DFS_DATANODE_USE_DN_HOSTNAME,
        DFSConfigKeys.DFS_DATANODE_USE_DN_HOSTNAME_DEFAULT);
    this.getHdfsBlockLocationsEnabled = conf.getBoolean(
        DFSConfigKeys.DFS_HDFS_BLOCKS_METADATA_ENABLED, 
        DFSConfigKeys.DFS_HDFS_BLOCKS_METADATA_ENABLED_DEFAULT);
    this.supergroup = conf.get(DFSConfigKeys.DFS_PERMISSIONS_SUPERUSERGROUP_KEY,
        DFSConfigKeys.DFS_PERMISSIONS_SUPERUSERGROUP_DEFAULT);
    this.isPermissionEnabled = conf.getBoolean(
        DFSConfigKeys.DFS_PERMISSIONS_ENABLED_KEY,
        DFSConfigKeys.DFS_PERMISSIONS_ENABLED_DEFAULT);
    this.pipelineSupportECN = conf.getBoolean(
        DFSConfigKeys.DFS_PIPELINE_ECN_ENABLED,
        DFSConfigKeys.DFS_PIPELINE_ECN_ENABLED_DEFAULT);

    confVersion = "core-" +
        conf.get("hadoop.common.configuration.version", "UNSPECIFIED") +
        ",hdfs-" +
        conf.get("hadoop.hdfs.configuration.version", "UNSPECIFIED");

    // Determine whether we should try to pass file descriptors to clients.
    if (conf.getBoolean(DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_KEY,
              DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_DEFAULT)) {
      String reason = DomainSocket.getLoadingFailureReason();
      if (reason != null) {
        LOG.warn("File descriptor passing is disabled because " + reason);
        this.fileDescriptorPassingDisabledReason = reason;
      } else {
        LOG.info("File descriptor passing is enabled.");
        this.fileDescriptorPassingDisabledReason = null;
      }
    } else {
      this.fileDescriptorPassingDisabledReason =
          "File descriptor passing was not configured.";
      LOG.debug(this.fileDescriptorPassingDisabledReason);
    }

    try {
      hostName = getHostName(conf);
      LOG.info("Configured hostname is " + hostName);
      startDataNode(conf, dataDirs, resources);
    } catch (IOException ie) {
      shutdown();
      throw ie;
    }
    final int dncCacheMaxSize =
        conf.getInt(DFS_DATANODE_NETWORK_COUNTS_CACHE_MAX_SIZE_KEY,
            DFS_DATANODE_NETWORK_COUNTS_CACHE_MAX_SIZE_DEFAULT) ;
    datanodeNetworkCounts =
        CacheBuilder.newBuilder()
            .maximumSize(dncCacheMaxSize)
            .build(new CacheLoader<String, Map<String, Long>>() {
              @Override
              public Map<String, Long> load(String key) throws Exception {
                final Map<String, Long> ret = new HashMap<String, Long>();
                ret.put("networkErrors", 0L);
                return ret;
              }
            });
  }

 
  
  private void initDataXceiver(Configuration conf) throws IOException {
    // find free port or use privileged port provided
    // TcpPeerServer 是对ServerSocket的封装，通过accept()方法返回Peer对象（对Socket的封装，提供通信的输入/输出流）
    TcpPeerServer tcpPeerServer;
    if (secureResources != null) {
      tcpPeerServer = new TcpPeerServer(secureResources);
    } else {
      tcpPeerServer = new TcpPeerServer(dnConf.socketWriteTimeout,
          DataNode.getStreamingAddr(conf));
    }
      
    // 设置TCP接收缓冲区 
    tcpPeerServer.setReceiveBufferSize(HdfsConstants.DEFAULT_DATA_SOCKET_SIZE);
    streamingAddr = tcpPeerServer.getStreamingAddr();
    LOG.info("Opened streaming server at " + streamingAddr);
    this.threadGroup = new ThreadGroup("dataXceiverServer");
      
    // 构造DataXceiverServer对象
    xserver = new DataXceiverServer(tcpPeerServer, conf, this);
    // 将DataXceiverServer线程组设置为守护线程
    this.dataXceiverServer = new Daemon(threadGroup, xserver);
    this.threadGroup.setDaemon(true); // auto destroy when empty

    // 短路读取情况
    if (conf.getBoolean(DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_KEY,
              DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_DEFAULT) ||
        conf.getBoolean(DFSConfigKeys.DFS_CLIENT_DOMAIN_SOCKET_DATA_TRAFFIC,
              DFSConfigKeys.DFS_CLIENT_DOMAIN_SOCKET_DATA_TRAFFIC_DEFAULT)) {
      // 构造DomainPeerServer（底层基于DomainSocket，用于本地进程间通信）  
      DomainPeerServer domainPeerServer =
                getDomainPeerServer(conf, streamingAddr.getPort());
      if (domainPeerServer != null) {
        // 构造localDataXceiverServer
        this.localDataXceiverServer = new Daemon(threadGroup,
            new DataXceiverServer(domainPeerServer, conf, this));
        LOG.info("Listening on UNIX domain socket: " +
            domainPeerServer.getBindPath());
      }
    }
    // 创建ShortCircuitRegistry对象
    this.shortCircuitRegistry = new ShortCircuitRegistry(conf);
  }

}
