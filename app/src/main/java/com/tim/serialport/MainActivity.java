package com.tim.serialport;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.tim.serialportlib.BytesUtil;
import com.tim.serialportlib.OnDataListener;
import com.tim.serialportlib.OnReportListener;
import com.tim.serialportlib.SerialPortError;
import com.tim.serialportlib.SerialPortManager;
import com.tim.serialportlib.SerialPortProtocol;

import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends Activity {

    private String TAG = getClass().getSimpleName();
    SerialPortManager  serialPortManager = new SerialPortManager();
    SerialPortProtocol protocol          = new SerialPortProtocol();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        model1();
        serialPortManager.enableDebug(true);
        serialPortManager.open("/dev/ttyS1", 9600);
    }

    /**
     * 自动粘包 无帧头帧尾, 无CRC校验
     */
    private void model1() {
        // 从收到第一个字节开始, 50毫秒后自动粘包
        serialPortManager.setReceivedTimeout(50);
        serialPortManager.setOnDataListener(new OnDataListener() {
            @Override
            public void onDataReceived(byte[] bytes) {
                Log.i(TAG, BytesUtil.toHexString(bytes));
            }
        });
    }

    /**
     * 固定长度, 无帧头,无帧尾
     */
    private void model2() {
        protocol.setFixedLength(20);
        serialPortManager.setProtocol(protocol);
        serialPortManager.setOnDataListener(new OnDataListener() {
            @Override
            public void onDataReceived(byte[] bytes) {
                Log.i(TAG, BytesUtil.toHexString(bytes));
            }
        });
    }

    /**
     * 固定长度, 有帧头, 无帧尾无CRC校验
     */
    private void model3() {
        protocol.setFrameHeader((byte) 0x02, (byte) 0x10);
        protocol.setFixedLength(20);
        serialPortManager.setProtocol(protocol);
        serialPortManager.setOnDataListener(new OnDataListener() {
            @Override
            public void onDataReceived(byte[] bytes) {
                Log.i(TAG, BytesUtil.toHexString(bytes));
            }
        });
    }

    /**
     * 可变长度, 有帧头, 帧尾, CRC校验
     * 异步回调
     */
    private void model4() {
        // 设置帧头
        protocol.setFrameHeader((byte) 0x09C, (byte) 0xC9);
        // 设置帧尾
        protocol.setFrameEnd((byte) 0x0E, (byte) 0x0A);
        // 设置CRC计算方式和范围, 结束范围可为负值
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.MODBUS_16, 2, -4);
        // 启用协议
        serialPortManager.setProtocol(protocol);
        // 设置数据超时时间, 超过此时间如果终端没有回复数据则调用 onFailure 方法
        serialPortManager.setReceivedTimeout(350);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                while (true) {
                    serialPortManager.sendHexString("6A A6 01 07 01 01 00 E4 48 0D 0A", new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes) {
                            Log.i(TAG, BytesUtil.toHexString(bytes));
                        }

                        @Override
                        public void onFailure(SerialPortError error) {
                            Log.i("onFailure", error.toString());
                        }

                        @Override
                        public void onComplete() {
                        }
                    });
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, 2000);
    }

    /**
     * 可变长度, 有帧头, 无帧尾, 有指定长度信息, 有CRC
     */
    private void model5() {
        // 设置帧头
        protocol.setFrameHeader((byte) 0x096, (byte) 0x69);
        protocol.setDataLenIndex(2);   // 数据长度下标
        protocol.setUselessLength(4);  // 除数据长度剩余长度, 不含帧头及数据本身, 因为此协议没有帧尾, 所以长度需要加上CRC的长度
        // 设置CRC计算方式和范围, 结束范围可为负值
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.CHECKSUM, 2, -2);
        // 启用协议
        serialPortManager.setProtocol(protocol);
        // 设置数据超时时间, 超过此时间如果终端没有回复数据则调用 onFailure 方法
        serialPortManager.setReceivedTimeout(350);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                while (true) {
                    serialPortManager.sendHexString("96 69 01 FE 01 C0 04 01 3A FE", new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes) {
                            // 自动粘包, 不返回帧头帧尾和CRC部分
                            Log.i(TAG, BytesUtil.toHexString(bytes));
                        }

                        @Override
                        public void onFailure(SerialPortError error) {
                            Log.i("onFailure", error.toString());
                        }

                        @Override
                        public void onComplete() {
                        }
                    });
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, 2000);
    }
}