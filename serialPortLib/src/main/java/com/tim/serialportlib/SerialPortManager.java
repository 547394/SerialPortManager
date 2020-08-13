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

    private String                        TAG             = getClass().getSimpleName();
    private SerialPortProtocol            protocol        = new SerialPortProtocol();
    private int                           bufferSize      = 128;
    private byte[]                        buffer          = new byte[bufferSize];
    private int                           bufferLength    = 0;
    private Timer                         receivedTimer;
    // 发关数据间隔
    private int                           sendInterval    = 50;
    // 接收数据间隔, 当没有帧头帧尾时有效
    private int                           receivedTimeout = 400;
    // 发送队列
    private LinkedList<SerialPortCommand> queueList       = new LinkedList<SerialPortCommand>();
    // 工作标志
    private boolean                       onWorking       = false;
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
        this.bufferSize = bufferSize;
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
                        SerialPortManager.this.onDataReceived(bytes);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onDataSend(byte[] bytes) {
                    Log.d("onDataSend", BytesUtil.toHexString(bytes));
                    if (onDataListener != null) {
                        onDataListener.onDataSend(bytes);
                    }
                    if (onReportListener != null) {
                        receivedTimer = new Timer();
                        receivedTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                Log.e(TAG, "RECEIVED_TIMEOUT");
                                if (onReportListener != null) {
                                    onReportListener.onFailure(SerialPortError.RECEIVED_TIMEOUT);
                                    onReportListener.onComplete();
                                }
                                cancelReceivedTimer();
                                clean();
                            }
                        }, receivedTimeout);
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

    public void sendBytes(byte[] bytes, OnReportListener listener) {
        queueList.offer(new SerialPortCommand(bytes, listener));
        sendNext();
    }

    public void sendHexString(String hexString, OnReportListener listener) {
        sendBytes(BytesUtil.toByteArray(hexString), listener);
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

    private void onDataReceived(byte[] bytes) throws Exception {
        // 粘包, 扛强干扰型, 可以兼容帧头和帧头有多余字节功能
        if (protocol.getFrameHeader() != null) {
            for (byte aByte : bytes) {
                buffer[bufferLength++] = aByte;
                // 查找帧头
                if (bufferLength == protocol.getFrameHeader().length) {
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
        if (bufferLength > protocol.getMinLength() && protocol.getFrameEnd() != null) {
            boolean isFindEnd = true;
            for (int j = protocol.getFrameEnd().length; j > 0; j--) {
                isFindEnd &= (buffer[bufferLength - j] == protocol.getFrameEnd()[protocol.getFrameEnd().length - j]);
            }
            if (isFindEnd) {
                cancelReceivedTimer();
                Log.d(TAG, BytesUtil.toHexString(buffer, bufferLength));
                boolean error      = false;
                int     offset     = protocol.getFrameEnd().length;
                byte[]  crc;
                int     crc_length = 0;
                int[]   area       = protocol.getCRCArea();
                int     area_start = area[0];
                int     area_end   = area[1] > area[0] ? area[1] : bufferLength + area[1];
                switch (protocol.getCRCModel()) {
                    case BCC:
                        crc_length = 1;
                        if (!(Crypto.crc_bcc(buffer, area_start, area_end) == buffer[bufferLength - 1 - offset])) {
                            error = true;
                        }
                        break;
                    case CHECKSUM:
                        crc = Crypto.crc_check_sum(buffer, area_start, area_end);
                        crc_length = 2;
                        if (!(crc[0] == buffer[bufferLength - 2 - offset] && crc[1] == buffer[bufferLength - 1 - offset])) {
                            error = true;
                        }
                        break;
                    case MODBUS_16:
                        crc = Crypto.crc_modbus_16(buffer, area_start, area_end);
                        crc_length = 2;
                        if (!(crc[0] == buffer[bufferLength - 2 - offset] && crc[1] == buffer[bufferLength - 1 - offset])) {
                            error = true;
                        }
                        break;
                }
                if (!error) {
                    byte[] dest = new byte[bufferLength - protocol.getFrameHeader().length - protocol.getFrameEnd().length - crc_length];
                    System.arraycopy(buffer, protocol.getFrameHeader().length, dest, 0, dest.length);
                    if (onDataListener != null) {
                        onDataListener.onDataReceived(dest);
                    }
                    if (onReportListener != null) {
                        onReportListener.onSuccess(dest);
                        onReportListener.onComplete();
                    }
                } else {
                    Log.e(TAG, "RECEIVED_CRC_ERROR");
                    if (onReportListener != null) {
                        onReportListener.onFailure(SerialPortError.RECEIVED_CRC_ERROR);
                        onReportListener.onComplete();
                    }
                }
                clean();
            }
        }
        // 没有帧头的情况超时自动拼接
        if (protocol.getFrameHeader() == null && protocol.getFrameEnd() == null && receivedTimer == null) {
            receivedTimer = new Timer();
            receivedTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    byte[] dest = new byte[bufferLength];
                    System.arraycopy(buffer, 0, dest, 0, dest.length);
                    if (onDataListener != null) {
                        onDataListener.onDataReceived(dest);
                    }
                    cancelReceivedTimer();
                    clean();
                }
            }, receivedTimeout);
        }
    }

    private void clean() {
        buffer           = new byte[bufferSize];
        bufferLength     = 0;
        onWorking        = false;
        onReportListener = null;
        if (queueList.size() > 0) {
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    sendNext();
                }
            }, sendInterval);
        }
    }

    public void cancelReceivedTimer() {
        try {
            if (receivedTimer != null) {
                receivedTimer.cancel();
                receivedTimer.purge();
                receivedTimer = null;
            }
        } catch (NullPointerException ignored) {
        }
    }

    public void close() {
        serialPortHelper.close();
    }
}
