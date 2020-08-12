package com.tim.serialportlib;

public interface OnDataReceivedTimeoutListener {

    void onTimeout(SerialPortError error);
}