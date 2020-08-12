package com.tim.serialportlib;

class SerialPortCommand {

    private byte[]           hexData;
    private OnReportListener listener;

    public SerialPortCommand(byte[] hexData, OnReportListener listener) {
        this.hexData  = hexData;
        this.listener = listener;
    }

    public byte[] getHexData() {
        return hexData;
    }

    public OnReportListener getListener() throws NullPointerException {
        return listener;
    }
}
