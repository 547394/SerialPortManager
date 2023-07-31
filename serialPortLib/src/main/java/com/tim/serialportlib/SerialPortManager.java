package com.tim.serialportlib;

import android.util.Log;

import java.io.File;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

import me.f1reking.serialportlib.SerialPortHelper;
import me.f1reking.serialportlib.listener.IOpenSerialPortListener;
import me.f1reking.serialportlib.listener.ISerialPortDataListener;
import me.f1reking.serialportlib.listener.Status;

public class SerialPortManager {

    private final String                        TAG             = getClass().getSimpleName();
    private       SerialPortProtocol            protocol        = new SerialPortProtocol();
    private       int                           bufferSize      = 1024;
    private       byte[]                        buffer          = new byte[bufferSize];
    private       int                           bufferLength    = 0;
    private       Timer                         receivedTimer;
    // 发关数据间隔
    private       int                           sendInterval    = 50;
    // 接收数据间隔, 当没有帧头帧尾时有效
    private       int                           receivedTimeout = 350;
    // 发送队列
    private final LinkedList<SerialPortCommand> queueList       = new LinkedList<SerialPortCommand>();
    // 工作标志
    private       boolean                       onWorking       = false;
    private       boolean                       debug           = false;
    SerialPortHelper  serialPortHelper;
    SerialPortCommand command;
    OnOpenListener    onOpenListener;
    OnDataListener    onDataListener;
    OnReportListener  onReportListener;

    public SerialPortManager() {
        serialPortHelper = new SerialPortHelper();
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
        this.bufferSize   = bufferSize;
        this.buffer       = new byte[bufferSize];
        this.bufferLength = 0;
    }

    public void setProtocol(SerialPortProtocol protocol) {
        this.protocol = protocol;
    }

    public String[] getAllDeicesPath() {
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
            public void onSuccess(File device) {
                if (onOpenListener != null) {
                    onOpenListener.onSuccess(device);
                }
            }

            @Override
            public void onFail(File device, Status status) {
                if (onOpenListener != null) {
                    onOpenListener.onFailure(device, SerialPortError.ACHIEVE_ERROR);
                }
            }
        });
        if (serialPortHelper.open()) {
            serialPortHelper.setISerialPortDataListener(new ISerialPortDataListener() {
                @Override
                public void onDataReceived(byte[] bytes) {
                    try {
                        logcat("onDataReceived(101):" + BytesUtil.toHexString(bytes));
                        SerialPortManager.this.onDataReceived(bytes);
                    } catch (Exception e) {
                        e.printStackTrace();
                        try {
                            if (onReportListener != null) {
                                onReportListener.onFailure(SerialPortError.ACHIEVE_ERROR, command.getFlag());
                                onReportListener.onComplete();
                            }
                            clean();
                        } catch (Exception ignored) {

                        }
                    }
                }

                @Override
                public void onDataSend(byte[] bytes) {
                    Log.d("onDataSend", BytesUtil.toHexString(bytes));
                    if (onDataListener != null) {
                        onDataListener.onDataSend(bytes);
                    }
                    try {
                        if (onReportListener != null) {
                            receivedTimer = new Timer();
                            try {
                                receivedTimer.schedule(new TimerTask() {
                                    @Override
                                    public void run() {
                                        Log.e(TAG, "RECEIVED_TIMEOUT");
                                        try {
                                            if (onReportListener != null) {
                                                onReportListener.onFailure(SerialPortError.RECEIVED_TIMEOUT, command.getFlag());
                                                onReportListener.onComplete();
                                            }
                                            clean();
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }, receivedTimeout);
                            } catch (Exception ignored) {

                            }
                        }
                    } catch (Exception ignored) {

                    }
                }
            });
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
        queueList.offer(new SerialPortCommand(bytes, protocol, flag, listener));
        sendNext();
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

    private void sendNext() {
        if (!onWorking) {
            try {
                command = queueList.poll();
            } catch (NoSuchElementException e) {
                queueList.clear();
                return;
            }
            if (command != null) {
                onWorking        = true;
                protocol         = command.getProtocol();
                onReportListener = command.getListener();
                // 发送之前重建缓冲区
                buffer       = new byte[bufferSize];
                bufferLength = 0;
                serialPortHelper.sendBytes(command.getHexData());
            }
        }
    }

    public boolean isOpen() {
        return serialPortHelper.isOpen();
    }

    private void onDataReceived(byte[] bytes) {
        // 粘包, 扛强干扰型, 可以兼容帧头和帧头有多余字节功能
        if (protocol.isSetFrameHeader()) {
            for (byte aByte : bytes) {
                buffer[bufferLength++] = aByte;
                // 查找帧头
                if (bufferLength == protocol.getFrameHeaderLength()) {
                    for (int i = 0; i < bufferLength; i++) {
                        if (!(buffer[i] == protocol.getFrameHeader()[i])) {
                            System.arraycopy(buffer, 1, buffer, 0, bufferLength--);
                            break;
                        }
                    }
                }
            }
        } else {
            for (byte aByte : bytes) {
                buffer[bufferLength++] = aByte;
            }
        }
        protocol.setBufferLength(bufferLength);
        if (protocol.getDataLenIndex() > 0 && protocol.getDataLength() == 0 && bufferLength >= protocol.getDataLenIndex()) {
            protocol.setDataLength(buffer[protocol.getDataLenIndex()]);
        }
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
                if (receivedTimer == null) {
                    receivedTimer = new Timer();
                    receivedTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            logcat("超时粘包模式");
                            output();
                        }
                    }, receivedTimeout);
                }
            }
        } else {
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
        byte[]  crc        = new byte[2];
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
            if (onReportListener != null) {
                onReportListener.onFailure(SerialPortError.RECEIVED_CRC_ERROR, command.getFlag());
                onReportListener.onComplete();
            }
        }
        return !error;
    }

    private void output() {
        cancelReceivedTimer();
        try {
            Log.d("output", BytesUtil.toHexString(buffer, bufferLength));
            if (calculateCRC()) {
                byte[] dest = new byte[bufferLength];
                System.arraycopy(buffer, 0, dest, 0, dest.length);
                if (onDataListener != null) {
                    onDataListener.onDataReceived(dest);
                }
                if (onReportListener != null) {
                    onReportListener.onSuccess(dest, command.getFlag());
                    onReportListener.onComplete();
                }
            }
        } catch (Exception ignored) {
            // java.lang.StringIndexOutOfBoundsException
        }
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        clean();
    }

    synchronized private void clean() {
        buffer           = new byte[bufferSize];
        bufferLength     = 0;
        onWorking        = false;
        onReportListener = null;
        if (queueList.size() > 0) {
            try {
                Thread.sleep(sendInterval);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            sendNext();
        }
    }

    public void cancelReceivedTimer() {
        try {
            if (receivedTimer != null) {
                receivedTimer.cancel();
                receivedTimer.purge();
                receivedTimer = null;
            }
        } catch (NullPointerException e) {
            e.printStackTrace();
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

    public void close() {
        serialPortHelper.close();
    }
}
