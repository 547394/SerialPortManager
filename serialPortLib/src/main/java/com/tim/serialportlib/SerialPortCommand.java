package com.tim.serialportlib;

class SerialPortCommand {

    private final byte[]             hexData;
    private final OnReportListener   listener;
    private final SerialPortProtocol protocol;
    private       int                flag = 0;

    public SerialPortCommand(byte[] hexData, SerialPortProtocol protocol, OnReportListener listener) {
        this.hexData  = hexData;
        this.protocol = protocol;
        this.listener = listener;
    }

    public SerialPortCommand(byte[] hexData, SerialPortProtocol protocol, int flag, OnReportListener listener) {
        this.hexData  = hexData;
        this.protocol = protocol;
        this.flag     = flag;
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

    public OnReportListener getListener() throws NullPointerException {
        return listener;
    }
}
