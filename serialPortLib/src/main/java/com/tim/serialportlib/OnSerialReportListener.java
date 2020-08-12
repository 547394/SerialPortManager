package com.tim.serialportlib;

abstract public class OnSerialReportListener {

    public void onSuccess(String data, byte[] bytes) {
    }

    public void onFailure(SerialPortError error) {
    }

    public void onComplete() {
    }
}
