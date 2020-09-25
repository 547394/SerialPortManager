package com.tim.serialportlib;

class SerialPortCommand {

    private final byte[]             hexData;
    private final OnReportListener   listener;
    private final SerialPortProtocol protocol;

    public SerialPortCommand(byte[] hexData, SerialPortProtocol protocol, OnReportListener listener) {
        this.hexData  = hexData;
        this.protocol = protocol;
        this.listener = listener;
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
