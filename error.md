### 实战错误分析
2019-02-19 20:03:55,665 | INFO  | IPC Server handler 2 on 25006 | Stopping services started for standby state | FSNamesystem.java:1308
2019-02-19 20:03:55,671 | WARN  | Edit log tailer | Edit log tailer interrupted | EditLogTailer.java:434
java.lang.InterruptedException: sleep interrupted
        at java.lang.Thread.sleep(Native Method)
        at org.apache.hadoop.hdfs.server.namenode.ha.EditLogTailer$EditLogTailerThread.doWork(EditLogTailer.java:432)
        at org.apache.hadoop.hdfs.server.namenode.ha.EditLogTailer$EditLogTailerThread.access$400(EditLogTailer.java:369)
        at org.apache.hadoop.hdfs.server.namenode.ha.EditLogTailer$EditLogTailerThread$1.run(EditLogTailer.java:386)
        at java.security.AccessController.doPrivileged(Native Method)
        at javax.security.auth.Subject.doAs(Subject.java:360)
        at org.apache.hadoop.security.UserGroupInformation.doAs(UserGroupInformation.java:1761)
        at org.apache.hadoop.security.SecurityUtil.doAsLoginUserOrFatal(SecurityUtil.java:480)
        at org.apache.hadoop.hdfs.server.namenode.ha.EditLogTailer$EditLogTailerThread.run(EditLogTailer.java:382)
2019-02-19 20:03:55,811 | INFO  | IPC Server handler 2 on 25006 | Starting services required for active state | FSNamesystem.java:1107
2019-02-19 20:03:55,870 | INFO  | IPC Server handler 2 on 25006 | Starting recovery process for unclosed journal segments... | QuorumJournalManager.java:437
2019-02-19 20:03:56,165 | INFO  | IPC Server handler 2 on 25006 | Successfully started new epoch 22 | QuorumJournalManager.java:439
2019-02-19 20:03:56,166 | INFO  | IPC Server handler 2 on 25006 | Beginning recovery of unclosed segment starting at txid 25095 | QuorumJournalManager.java:261
2019-02-19 20:03:56,252 | INFO  | IPC Server handler 2 on 25006 | Recovery prepare phase complete. Responses:
189.154.5.96:25012: segmentState { startTxId: 25095 endTxId: 25115 isInProgress: false } lastWriterEpoch: 20 lastCommittedTxId: 25114
189.154.5.33:25012: segmentState { startTxId: 25095 endTxId: 25115 isInProgress: false } lastWriterEpoch: 20 lastCommittedTxId: 25114 | QuorumJournalManager.java:270
2019-02-19 20:03:56,254 | INFO  | IPC Server handler 2 on 25006 | Using longest log: 189.154.5.96:25012=segmentState {
  startTxId: 25095
  endTxId: 25115
  isInProgress: false
}
lastWriterEpoch: 20
lastCommittedTxId: 25114
 | QuorumJournalManager.java:294
2019-02-19 20:03:56,313 | INFO  | IPC Server handler 2 on 25006 | Recovering unfinalized segments in /srv/BigData/namenode/current | FileJournalManager.java:388
2019-02-19 20:03:56,331 | INFO  | IPC Server handler 2 on 25006 | Catching up to latest edits from old active before taking over writer role in edits logs | FSNamesystem.java:1118
2019-02-19 20:03:56,383 | INFO  | IPC Server handler 2 on 25006 | Reading org.apache.hadoop.hdfs.server.namenode.RedundantEditLogInputStream@2672d95e expecting start txid #25116 | FSImage.java:908
2019-02-19 20:03:56,384 | INFO  | IPC Server handler 2 on 25006 | Start loading edits file /srv/BigData/namenode/current/edits_0000000000000025637-0000000000000025645 | FSEditLogLoader.java:143
2019-02-19 20:03:56,384 | INFO  | IPC Server handler 2 on 25006 | Fast-forwarding stream '/srv/BigData/namenode/current/edits_0000000000000025637-0000000000000025645' to transaction ID 25116 | RedundantEditLogInputStream.java:176
2019-02-19 20:03:56,387 | ERROR | IPC Server handler 2 on 25006 | Error encountered requiring NN shutdown. Shutting down immediately. | NameNode.java:1877
java.io.IOException: There appears to be a gap in the edit log.  We expected txid 25116, but got txid 25637.
        at org.apache.hadoop.hdfs.server.namenode.MetaRecoveryContext.editLogLoaderPrompt(MetaRecoveryContext.java:94)
        at org.apache.hadoop.hdfs.server.namenode.FSEditLogLoader.loadEditRecords(FSEditLogLoader.java:216)
        at org.apache.hadoop.hdfs.server.namenode.FSEditLogLoader.loadFSEdits(FSEditLogLoader.java:144)
        at org.apache.hadoop.hdfs.server.namenode.FSImage.loadEdits(FSImage.java:911)
        at org.apache.hadoop.hdfs.server.namenode.FSImage.loadEdits(FSImage.java:892)
        at org.apache.hadoop.hdfs.server.namenode.ha.EditLogTailer.doTailEdits(EditLogTailer.java:281)
        at org.apache.hadoop.hdfs.server.namenode.ha.EditLogTailer$1.run(EditLogTailer.java:237)
        at org.apache.hadoop.hdfs.server.namenode.ha.EditLogTailer$1.run(EditLogTailer.java:231)
        at java.security.AccessController.doPrivileged(Native Method)
        at javax.security.auth.Subject.doAs(Subject.java:422)
        at org.apache.hadoop.security.UserGroupInformation.doAs(UserGroupInformation.java:1781)
        at org.apache.hadoop.security.SecurityUtil.doAsUser(SecurityUtil.java:515)
        at org.apache.hadoop.security.SecurityUtil.doAsLoginUser(SecurityUtil.java:496)
        at org.apache.hadoop.hdfs.server.namenode.ha.EditLogTailer.catchupDuringFailover(EditLogTailer.java:231)
        at org.apache.hadoop.hdfs.server.namenode.FSNamesystem.startActiveServices(FSNamesystem.java:1120)
        at org.apache.hadoop.hdfs.server.namenode.NameNode$NameNodeHAContext.startActiveServices(NameNode.java:1901)
        at org.apache.hadoop.hdfs.server.namenode.ha.ActiveState.enterState(ActiveState.java:61)
        at org.apache.hadoop.hdfs.server.namenode.ha.HAState.setStateInternal(HAState.java:64)
        at org.apache.hadoop.hdfs.server.namenode.ha.StandbyState.setState(StandbyState.java:49)
        at org.apache.hadoop.hdfs.server.namenode.NameNode.transitionToActive(NameNode.java:1767)
        at org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer.transitionToActive(NameNodeRpcServer.java:1667)
        at org.apache.hadoop.ha.protocolPB.HAServiceProtocolServerSideTranslatorPB.transitionToActive(HAServiceProtocolServerSideTranslatorPB.java:107)
        at org.apache.hadoop.ha.proto.HAServiceProtocolProtos$HAServiceProtocolService$2.callBlockingMethod(HAServiceProtocolProtos.java:4460)
        at org.apache.hadoop.ipc.ProtobufRpcEngine$Server$ProtoBufRpcInvoker.call(ProtobufRpcEngine.java:616)
        at org.apache.hadoop.ipc.RPC$Server.call(RPC.java:973)
        at org.apache.hadoop.ipc.Server$Handler$1.run(Server.java:2260)
        at org.apache.hadoop.ipc.Server$Handler$1.run(Server.java:2256)
        at java.security.AccessController.doPrivileged(Native Method)
        at javax.security.auth.Subject.doAs(Subject.java:422)
        at org.apache.hadoop.security.UserGroupInformation.doAs(UserGroupInformation.java:1781)
        at org.apache.hadoop.ipc.Server$Handler.run(Server.java:2254)
2019-02-19 20:03:56,390 | INFO  | IPC Server handler 2 on 25006 | Exiting with status 1 | ExitUtil.java:124
2019-02-19 20:03:56,394 | INFO  | pool-1-thread-1 | SHUTDOWN_MSG:
/************************************************************
SHUTDOWN_MSG: Shutting down NameNode at szvphicprb14933/189.154.5.70
************************************************************/ | LogAdapter.java:47

