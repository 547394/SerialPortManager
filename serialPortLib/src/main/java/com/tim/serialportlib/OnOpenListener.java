package com.tim.serialportlib;

import android.util.Log;

import java.io.File;

abstract public class OnOpenListener {

    private String TAG = getClass().getSimpleName();

    public void onSuccess(File device) {
    }

    public void onFailure(File device, SerialPortError error) {
        Log.e(TAG, "onFailure: " + device.getPath() + " error:" + error.toString());
    }
}