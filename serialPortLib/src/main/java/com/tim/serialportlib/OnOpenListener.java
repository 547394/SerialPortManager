package com.tim.serialportlib;

import android.util.Log;

import java.io.File;

/**
 * 串口打开结果监听器。
 * <p>
 * 通过 {@link SerialPortManager#setOnOpenListener(OnOpenListener)} 注册。
 * 与其它回调不同，本监听器的回调被投递到<b>主线程</b>，可以直接操作 UI。
 * <p>
 * 注意：{@link SerialPortManager#open(String, int)} 没有返回值，打开失败也不会抛异常，
 * 结果一律以本监听器为准；回调被抛送到主线程，因此可能晚于 open() 返回。
 */
abstract public class OnOpenListener {

    private final String TAG = getClass().getSimpleName();

    /**
     * 串口打开成功。在主线程回调。
     *
     * @param device 已打开的串口设备文件
     */
    public void onSuccess(File device) {
    }

    /**
     * 串口打开失败。在主线程回调，默认实现打印一条错误日志，可按需覆盖。
     *
     * @param device 打开失败的串口设备文件
     * @param error  错误类型，当前固定为 {@link SerialPortError#ACHIEVE_ERROR}
     */
    public void onFailure(File device, SerialPortError error) {
        Log.e(TAG, "onFailure: " + device.getPath() + " error:" + error.toString());
    }
}
