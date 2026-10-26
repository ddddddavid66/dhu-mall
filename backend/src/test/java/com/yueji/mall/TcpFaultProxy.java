package com.yueji.mall;

import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 测试专用真实TCP故障边界，不停止共享服务，也不替换业务适配器。 */
final class TcpFaultProxy implements AutoCloseable {
    private final ServerSocket listener;
    private final int targetPort;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private volatile boolean available=true;
    TcpFaultProxy(int targetPort) {
        this.targetPort=targetPort;
        try {listener=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));}
        catch(IOException error) {throw new IllegalStateException(error);}
        Thread.startVirtualThread(this::accept);
    }
    int port() {return listener.getLocalPort();}
    void available(boolean value) {available=value;if(!value) sockets.forEach(this::closeSocket);}
    private void accept() {
        while(!listener.isClosed()) {
            try {
                Socket source=listener.accept();
                if(!available) {source.close();continue;}
                Socket target=new Socket("127.0.0.1",targetPort);
                sockets.add(source);sockets.add(target);
                Thread.startVirtualThread(()->copy(source,target));
                Thread.startVirtualThread(()->copy(target,source));
            } catch(IOException ignored) { /* 关闭或故障注入期间不重写测试结果。 */ }
        }
    }
    private void copy(Socket source,Socket target) {
        try {source.getInputStream().transferTo(target.getOutputStream());}
        catch(IOException expected) { /* TCP中断是本夹具要注入的故障。 */ }
        finally {closeSocket(source);closeSocket(target);}
    }
    private void closeSocket(Socket socket) {sockets.remove(socket);try {socket.close();}catch(IOException ignored) {}}
    public void close() {available(false);try {listener.close();}catch(IOException ignored) {}}
}
