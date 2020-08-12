package com.tim.serialportlib;

class SerialPortCommand {

    int                    flag;
    byte                   fnCode;
    byte[]                 data;
    OnSerialReportListener listener;

    public SerialPortCommand(int flag, byte fnCode, byte[] data, OnSerialReportListener listener) {
        this.flag     = flag;
        this.fnCode   = fnCode;
        this.data     = data;
        this.listener = listener;
    }

    public byte[] getHexData() {
        return data;
    }

    public OnSerialReportListener getListener() throws NullPointerException {
        return listener;
    }
}
