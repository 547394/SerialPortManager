package com.tim.serialportlib;

import java.lang.ref.WeakReference;

class SerialPortCommand {

    private final byte[]             hexData;
    private final OnReportListener   listener;
    private final SerialPortProtocol protocol;
    private       int                flag = 0;

    public SerialPortCommand(byte[] hexData, SerialPortProtocol protocol, OnReportListener listener) {
        this.hexData = hexData;
        this.protocol = protocol;
        this.listener = listener;
    }

    public SerialPortCommand(byte[] hexData, SerialPortProtocol protocol, int flag, OnReportListener listener) {
        this.hexData = hexData;
        this.protocol = protocol;
        this.flag = flag;
        this.listener = listener;
    }

    public int getFlag() {
        return flag;
    }

    public void setFlag(int flag) {
        this.flag = flag;
    }

    public byte[] getHexData() {
        return hexData;
    }

    public SerialPortProtocol getProtocol() {
        return protocol;
    }

    /**
     * 获取此命令的监听器.
     *
     * @return OnReportListener 实例，如果它已经被垃圾回收，则返回 null.
     */
    public OnReportListener getListener() {
        return listener;
    }
}
