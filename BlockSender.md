 ```  
                   Packet
   __________________|____________________
   | PacketHeader | CHECKSUMS |   DATA   |
   ———————————————————————————————————————
```

sendBlock()发送数据包主要是通过sendPacket()方法，sendPacket()方法可分为3部分。
· 首先计算数据包头域在pkt缓存中的位置headerOff，再计算checksum在pkt中的位置checksumOff，以及实际数据在pkt中的位置dataOff。然后将数据包头域、校验数据   以及实际数据写入pkt缓存中。如果verifyChecksum属性被设置为true，则调用verifyChecksum()方法确认校验和数据正确。
· 发送数据块，将pkt缓存中的数据写入IO流中。
· 使用节流器控制写入的速度。
