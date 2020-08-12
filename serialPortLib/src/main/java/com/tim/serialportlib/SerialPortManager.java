package com.tim.serialportlib;

import android.util.Log;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

import me.f1reking.serialportlib.SerialPortHelper;
import me.f1reking.serialportlib.listener.IOpenSerialPortListener;
import me.f1reking.serialportlib.listener.ISerialPortDataListener;
import me.f1reking.serialportlib.listener.Status;

public class SerialPortManager {

    private String              TAG             = getClass().getSimpleName();
    private SerialPortProtocol  protocol        = new SerialPortProtocol();
    private SerialPortDataQueue serialPortDataQueue;
    private int                 bufferSize      = 128;
    private byte[]              buffer          = new byte[bufferSize];
    private int                 bufferLength    = 0;
    private int                 receivedTimeout = 100;  // 串口接收数据超时时间
    private Timer               receivedTimer;
    //
    SerialPortHelper              serialPortHelper;
    OnOpenListener                onOpenListener;
    OnDataListener                onDataListener;
    OnDataReceivedTimeoutListener onDataReceivedTimeout;

    public SerialPortManager() {
        serialPortHelper = new SerialPortHelper();
    }

    public boolean open(String port, int baudRate) {
        serialPortHelper.setPort(port);
        serialPortHelper.setBaudRate(baudRate);
        serialPortHelper.setStopBits(1);
        return serialPortHelper.open();
    }

    public void sendQueueCommand(SerialPortCommand command) {
        if (serialPortDataQueue == null) {
            serialPortDataQueue = new SerialPortDataQueue();
            serialPortDataQueue.start();
        }
        serialPortDataQueue.offer(command);
    }

    public boolean sendBytes(byte[] bytes) {
        return serialPortHelper.sendBytes(bytes);
    }

    public boolean sendHex(String hex) {
        if (serialPortHelper.isOpen()) {
            return serialPortHelper.sendBytes(BytesUtil.toByteArray(hex));
        } else {
            Log.e(TAG, "串口未打开");
            return false;
        }
    }

    public boolean isOpen() {
        return serialPortHelper.isOpen();
    }

    public void setOnOpenListener(OnOpenListener listener) {
        this.onOpenListener = listener;
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
    }

    boolean isFindHeader = false;
    boolean isFindEnd    = false;

    public void setOnSerialPortDataListener(OnDataListener listener) {
        this.onDataListener = listener;
        serialPortHelper.setISerialPortDataListener(new ISerialPortDataListener() {
            @Override
            public void onDataReceived(byte[] bytes) {
                // 粘包, 扛强干扰型, 可以兼容帧头和帧头有多余字节功能
                for (byte aByte : bytes) {
                    if (!isFindHeader && aByte == protocol.getFrameHeader()[0]) {
                        isFindHeader = true;
                    }
                    if (isFindHeader && !isFindEnd) {
                        buffer[bufferLength++] = aByte;
                        if (bufferLength == 2 && (aByte != protocol.getFrameHeader()[1])) {
                            buffer[0]    = buffer[1];
                            bufferLength = 1;
                        }
                    }
                }
                if (bufferLength > protocol.getMinLength() && !isFindEnd) {
                    if (protocol.getFrameEnd().length == 1 && buffer[bufferLength - 1] == protocol.getFrameEnd()[0]) {
                        isFindEnd = true;
                    }
                    if (protocol.getFrameEnd().length == 2 && (buffer[bufferLength - 2] == protocol.getFrameEnd()[0]) && buffer[bufferLength - 1] == protocol.getFrameEnd()[1]) {
                        isFindEnd = true;
                    }
                    if (isFindHeader && isFindEnd) {
                        cancelReceiverTimer();
                        Log.e(TAG, "onDataReceived:" + BytesUtil.toHexString(buffer, bufferLength).toUpperCase());
                        boolean error  = false;
                        int     offset = protocol.getFrameEnd().length;
                        byte[]  crc;
                        switch (protocol.getCRCModel()) {
                            case BCC:
                                if (!(Crypto.crc_bcc(buffer, protocol.getCRCArea()[0], protocol.getCRCArea()[1]) == buffer[bufferLength - 1 - offset])) {
                                    error = true;
                                }
                                break;
                            case CHECKSUM:
                                crc = Crypto.crc_check_sum(buffer, protocol.getCRCArea()[0], protocol.getCRCArea()[1]);
                                if (!(crc[0] == buffer[bufferLength - 2 - offset] && crc[1] == buffer[bufferLength - 1 - offset])) {
                                    error = true;
                                }
                                break;
                            case MODBUS_16:
                                int[] area = protocol.getCRCArea();
                                int end = area[1] > area[0] ? area[1] : bufferLength + area[1];
                                crc = Crypto.crc_modbus_16(buffer, area[0], end);
                                if (!(crc[0] == buffer[bufferLength - 2 - offset] && crc[1] == buffer[bufferLength - 1 - offset])) {
                                    error = true;
                                }
                                break;
                        }
                        if (!error) {
                            byte[] dest = new byte[bufferLength - protocol.getFrameHeader().length - protocol.getFrameEnd().length];
                            System.arraycopy(buffer, protocol.getFrameHeader().length, dest, 0, dest.length);
                            onDataListener.onDataReceived(dest);
                        } else {
                            Log.e(TAG, "RECEIVED_CRC_ERROR");
                            if (onDataReceivedTimeout != null) {
                                onDataReceivedTimeout.onTimeout(SerialPortError.RECEIVED_CRC_ERROR);
                            }
                        }
                        clean();
                    }
                }
            }

            @Override
            public void onDataSend(byte[] bytes) {
                if (onDataListener != null) {
                    onDataListener.onDataSend(bytes);
                    if (onDataReceivedTimeout != null) {
                        receivedTimer = new Timer();
                        receivedTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                Log.e(TAG, "RECEIVED_TIMEOUT");
                                onDataReceivedTimeout.onTimeout(SerialPortError.RECEIVED_TIMEOUT);
                                clean();
                            }
                        }, receivedTimeout);
                    }
                }
            }
        });
    }

    private void clean() {
        isFindHeader = false;
        isFindEnd    = false;
        buffer       = new byte[bufferSize];
        bufferLength = 0;
        if (serialPortDataQueue != null) {
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    serialPortDataQueue.unPark();
                }
            }, 50);
        }
    }

    public void setOnDataReceivedTimeout(int timeout, OnDataReceivedTimeoutListener onDataReceivedTimeout) {
        this.receivedTimeout       = timeout;
        this.onDataReceivedTimeout = onDataReceivedTimeout;
    }

    public void cancelReceiverTimer() {
        try {
            if (receivedTimer != null) {
                receivedTimer.cancel();
                receivedTimer.purge();
                receivedTimer = null;
            }
        } catch (NullPointerException ignored) {
        }
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public void setProtocol(SerialPortProtocol protocol) {
        this.protocol = protocol;
    }

    public void close() {
        serialPortHelper.close();
    }
}
