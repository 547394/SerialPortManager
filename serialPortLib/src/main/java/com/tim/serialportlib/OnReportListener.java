package com.tim.serialportlib;

abstract public class OnReportListener {

    public void onSuccess(byte[] bytes, int flag) {
    }

    public void onFailure(SerialPortError error, int flag) {
    }

    public void onComplete() {
    }
}
