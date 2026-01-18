package com.tim.serialportlib;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.LinkedList;
import java.util.NoSuchElementException;

import me.f1reking.serialportlib.SerialPortHelper;
import me.f1reking.serialportlib.listener.IOpenSerialPortListener;
import me.f1reking.serialportlib.listener.ISerialPortDataListener;
import me.f1reking.serialportlib.listener.Status;

public class SerialPortManager {

    private final    String                        TAG             = getClass().getSimpleName();
    private          SerialPortProtocol            protocol        = new SerialPortProtocol();
    private          int                           bufferSize      = 2048;
    private          byte[]                        buffer          = new byte[bufferSize];
    private          int                           bufferLength    = 0;
    // 使用 HandlerThread 和 Handler 替代 Timer 和 Thread.sleep
    private final    Handler                       mMainHandler;
    private final    Handler                       mWorkHandler;
    private final    HandlerThread                 mWorkThread;
    // 发关数据间隔
    private          int                           sendInterval    = 50;
    // 接收数据间隔, 当没有帧头帧尾时有效
    private          int                           receivedTimeout = 350;
    // 发送队列
    private final    LinkedList<SerialPortCommand> queueList       = new LinkedList<>();
    // 工作标志
    private volatile boolean                       onWorking       = false;
    private          boolean                       debug           = false;
    SerialPortHelper  serialPortHelper;
    SerialPortCommand command;
    OnOpenListener    onOpenListener;
    OnDataListener    onDataListener;

    public SerialPortManager() {
        serialPortHelper = new SerialPortHelper();
        mMainHandler = new Handler(Looper.getMainLooper());

        // 初始化后台线程和 Handler
        mWorkThread = new HandlerThread("SerialPortManager-WorkThread");
        mWorkThread.start();
        mWorkHandler = new Handler(mWorkThread.getLooper());
    }

    public void setOnOpenListener(OnOpenListener listener) {
        this.onOpenListener = listener;
    }

    public void setOnDataListener(OnDataListener listener) {
        this.onDataListener = listener;
    }

    public void setSendInterval(int interval) {
        sendInterval = interval;
    }

    public void setReceivedTimeout(int timeout) {
        receivedTimeout = timeout;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
        synchronized (this) { // 加锁防止重置时并发
            this.buffer = new byte[bufferSize];
            this.bufferLength = 0;
        }
    }

    public void setProtocol(SerialPortProtocol protocol) {
        this.protocol = protocol;
    }

    public void removeOnOpenListener() {
        this.onOpenListener = null;
    }

    public void removeOnDataListener() {
        this.onDataListener = null;
    }

    public String[] getAllDevicesPath() {
        return serialPortHelper.getAllDeicesPath();
    }

    public void open(String port, int baudRate) {
        open(port, baudRate, 8, 0, 1);
    }

    public void open(String port, int baudRate, int dataBits, int parity, int stopBits) {
        serialPortHelper.setPort(port);
        serialPortHelper.setBaudRate(baudRate);
        serialPortHelper.setDataBits(dataBits);
        serialPortHelper.setParity(parity);
        serialPortHelper.setStopBits(stopBits);
        serialPortHelper.setIOpenSerialPortListener(new IOpenSerialPortListener() {
            @Override
            public void onSuccess(final File device) {
                if (onOpenListener != null) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onOpenListener.onSuccess(device);
                        }
                    });
                }
            }

            @Override
            public void onFail(final File device, Status status) {
                if (onOpenListener != null) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onOpenListener.onFailure(device, SerialPortError.ACHIEVE_ERROR);
                        }
                    });
                }
            }
        });
        if (serialPortHelper.open()) {
            serialPortHelper.setISerialPortDataListener(new ISerialPortDataListener() {
                @Override
                public void onDataReceived(byte[] bytes) {
                    try {
                        logcat("onDataReceived(132):" + BytesUtil.toHexString(bytes));
                        processDataReceived(bytes);
                    } catch (Exception e) {
                        err("onDataReceivedError(135):" + e.getMessage());
                        notifyFailure(SerialPortError.ACHIEVE_ERROR);
                        clean();
                    }
                }

                @Override
                public void onDataSend(byte[] bytes) {
                    Log.d("onDataSend", BytesUtil.toHexString(bytes));
                    if (onDataListener != null) {
                        onDataListener.onDataSend(bytes);
                    }
                    if (command != null) {
                        cancelReceivedTimer();
                        mWorkHandler.postDelayed(mResponseTimeoutRunnable, receivedTimeout);
                    }
                }
            });
        }
    }

    // 超时任务 Runnable
    private final Runnable mResponseTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            Log.e(TAG, "RECEIVED_TIMEOUT for flag: " + (command != null ? command.getFlag() : "N/A"));
            notifyFailure(SerialPortError.RECEIVED_TIMEOUT);
            clean();
        }
    };

    private final Runnable mStickyPacketRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (SerialPortManager.this) {
                output();
            }
        }
    };

    private void notifyFailure(SerialPortError error) {
        OnReportListener listener = (command != null) ? command.getListener() : null;
        if (listener != null) {
            listener.onFailure(error, command.getFlag());
            listener.onComplete();
        }
    }

    public boolean sendBytes(byte[] bytes) {
        if (serialPortHelper.isOpen()) {
            return serialPortHelper.sendBytes(bytes);
        } else {
            Log.e(TAG, "串口未打开");
            return false;
        }
    }

    public boolean sendHexString(String hex) {
        return sendBytes(BytesUtil.toByteArray(hex));
    }

    public void sendBytes(byte[] bytes, SerialPortProtocol protocol, int flag, OnReportListener listener) {
        synchronized (queueList) {
            queueList.offer(new SerialPortCommand(bytes, protocol, flag, listener));
        }
        triggerSendNext();
    }

    public void sendHexString(String hexString, SerialPortProtocol protocol, int flag, OnReportListener listener) {
        sendBytes(BytesUtil.toByteArray(hexString), protocol, flag, listener);
    }

    public void sendBytes(byte[] bytes, SerialPortProtocol protocol, OnReportListener listener) {
        sendBytes(bytes, protocol, 0, listener);
    }

    synchronized public void sendHexString(String hexString, SerialPortProtocol protocol, OnReportListener listener) {
        sendHexString(hexString, protocol, 0, listener);
    }

    private void triggerSendNext() {
        mWorkHandler.post(new Runnable() {
            @Override
            public void run() {
                sendNext();
            }
        });
    }

    synchronized private void sendNext() {
        if (!onWorking) {
            try {
                synchronized (queueList) {
                    command = queueList.poll();
                }
            } catch (NoSuchElementException e) {
                err(e.getMessage());
                queueList.clear();
                return;
            }
            if (command != null) {
                onWorking = true;
                protocol = command.getProtocol();
                buffer = new byte[bufferSize];
                bufferLength = 0;
                if (!serialPortHelper.sendBytes(command.getHexData())) {
                    clean();
                }
            }
        }
    }

    public boolean isOpen() {
        return serialPortHelper.isOpen();
    }

    private synchronized void processDataReceived(byte[] bytes) {
        // 粘包, 扛强干扰型, 可以兼容帧头和帧头有多余字节功能
        if (protocol.isSetFrameHeader()) {
            for (byte aByte : bytes) {
                if (bufferLength < bufferSize) {
                    buffer[bufferLength++] = aByte;
                } else {
                    // 缓冲区满了还没解析出完整包，说明数据异常，强制清理
                    clean();
                    break;
                }
                // 查找帧头, 可以兼容帧头有多余字节功能
                if (bufferLength == protocol.getFrameHeaderLength()) {
                    for (int i = 0; i < bufferLength; i++) {
                        if (!(buffer[i] == protocol.getFrameHeader()[i])) {
                            // 发现第一个字节不是帧头，整体左移1位
                            System.arraycopy(buffer, 1, buffer, 0, bufferLength - 1);
                            bufferLength--;
                            // 注意：这里需要跳出内层循环，让外层循环继续填充 buffer
                            break;
                        }
                    }
                }
            }
        } else {
            for (byte aByte : bytes) {
                if (bufferLength < bufferSize) {
                    buffer[bufferLength++] = aByte;
                } else {
                    // 缓冲区满了还没解析出完整包，说明数据异常，强制清理
                    clean();
                    break;
                }
            }
        }
        protocol.setBufferLength(bufferLength);
        if (protocol.getDataLenIndex() > 0 && protocol.getDataLength() == 0 && bufferLength > protocol.getDataLenIndex()) {
            protocol.setDataLength(BytesUtil.byte2int(buffer[protocol.getDataLenIndex()]));
        }
        // 可变长度模式
        if (protocol.getProtocolModel() == SerialPortProtocol.PROTOCOL_MODEL.VARIABLE) {
            if (protocol.isSetFrameHeader()) {
                if (bufferLength >= protocol.getMinDataLength()) {
                    // 校验帧尾
                    if (protocol.isSetFrameEnd()) {
                        boolean isFindEnd = true;
                        for (int j = protocol.getFrameEndLength(); j > 0; j--) {
                            isFindEnd &= (buffer[bufferLength - j] == protocol.getFrameEnd()[protocol.getFrameEndLength() - j]);
                        }
                        if (isFindEnd && bufferLength == protocol.getFrameLength()) {
                            logcat("有帧头帧尾模式");
                            output();
                        }
                    } else {
                        if (bufferLength - 1 == protocol.getFrameLength()) {
                            logcat("有帧头指定长度信息模式");
                            output();
                        }
                    }
                }
            } else {
                // 没有定义帧头则认为没有定义协议, 启用超时粘包
                cancelReceivedTimer();
                mWorkHandler.postDelayed(mStickyPacketRunnable, receivedTimeout);
            }
        } else {
            // 固定长度模式, 数据达到指定长度则输出
            if (bufferLength == protocol.getLength()) {
                logcat("固定长度模式");
                output();
            }
        }
    }

    // 计算并判断CRC, 如果CRC正确则返回 true 否则返回false
    private boolean calculateCRC() {
        boolean error      = false;
        int     offset     = protocol.getFrameEndLength();
        int[]   area       = protocol.getCRCArea();
        int     area_start = area[0];
        int     area_end   = area[1] > area[0] ? area[1] : bufferLength + area[1];
        if (area_end > bufferLength) area_end = bufferLength;
        byte[] crc = new byte[2];
        switch (protocol.getCRCModel()) {
            case BCC:
                logcat("CRC area:" + area_start + " - " + area_end);
                crc[0] = Crypto.crc_bcc(buffer, area_start, area_end);
                if (!(crc[0] == buffer[bufferLength - 1 - offset])) {
                    error = true;
                }
                break;
            case CHECKSUM:
                crc = Crypto.crc_check_sum(buffer, area_start, area_end);
                if (!(crc[0] == buffer[bufferLength - 2 - offset] && crc[1] == buffer[bufferLength - 1 - offset])) {
                    error = true;
                }
                break;
            case MODBUS_16:
                crc = Crypto.crc_modbus_16(buffer, area_start, area_end);
                if (!(crc[0] == buffer[bufferLength - 2 - offset] && crc[1] == buffer[bufferLength - 1 - offset])) {
                    error = true;
                }
                break;
            case MODBUS_16_RTU:
                crc = Crypto.crc_modbus_16(buffer, area_start, area_end);
                if (!(crc[0] == buffer[bufferLength - 1 - offset] && crc[1] == buffer[bufferLength - 2 - offset])) {
                    error = true;
                }
                break;
        }
        if (error) {
            Log.e(TAG, "RECEIVED_CRC_ERROR :" + BytesUtil.toHexString(crc));
            OnReportListener listener = (command != null) ? command.getListener() : null;
            if (listener != null) {
                listener.onFailure(SerialPortError.RECEIVED_CRC_ERROR, command.getFlag());
                listener.onComplete();
            }
        }
        return !error;
    }

    private void output() {
        cancelReceivedTimer();
        byte[] dest = new byte[bufferLength];
        System.arraycopy(buffer, 0, dest, 0, dest.length);
        Log.d("output", BytesUtil.toHexString(buffer, bufferLength));
        if (calculateCRC()) {
            if (onDataListener != null) {
                onDataListener.onDataReceived(dest);
            }
            OnReportListener listener = (command != null) ? command.getListener() : null;
            if (listener != null) {
                listener.onSuccess(dest, command.getFlag());
                listener.onComplete();
            }
        }
        clean();
    }

    synchronized private void clean() {
        cancelReceivedTimer();
//        buffer = new byte[bufferSize];
        bufferLength = 0;
        onWorking = false;
        command = null;
        if (!queueList.isEmpty()) {
            mWorkHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    triggerSendNext();
                }
            }, sendInterval);
        }
    }

    public void cancelReceivedTimer() {
        if (mWorkHandler != null) {
            mWorkHandler.removeCallbacks(mResponseTimeoutRunnable);
            mWorkHandler.removeCallbacks(mStickyPacketRunnable);
        }
    }

    public void enableDebug(boolean debug) {
        this.debug = debug;
    }

    private void logcat(String msg) {
        if (debug) {
            Log.d(TAG, msg);
        }
    }

    private void err(String msg) {
        if (debug) {
            Log.e(TAG, msg);
        }
    }

    public void close() {
        if (serialPortHelper != null) {
            serialPortHelper.close();
        }
        removeOnDataListener();
        removeOnOpenListener();

        if (mWorkThread != null) {
            mWorkThread.quitSafely();
        }

        synchronized (queueList) {
            queueList.clear();
        }

        if (mWorkHandler != null) {
            mWorkHandler.removeCallbacksAndMessages(null);
        }

        onWorking = false;
    }
}
