package com.tim.serialportlib;

abstract public class OnReportListener {

    public void onSuccess(byte[] bytes) {
    }

    public void onFailure(SerialPortError error) {
    }

    public void onComplete() {
    }
}
