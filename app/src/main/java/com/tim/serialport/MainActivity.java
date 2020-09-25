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
        // model1();
        serialPortManager.enableDebug(true);
        serialPortManager.open("/dev/ttyS0", 9600);
    }

    private void model0() {
        protocol.setFrameHeader((byte) 0xEA);
        protocol.setFrameEnd((byte) 0xF5);
        protocol.setDataLenIndex(3);   // 数据长度下标
        protocol.setUselessLength(2);
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.BCC, 3, -2);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                while (true) {
                    serialPortManager.sendHexString("01 EA D1 01 04 FF 11 EA F5", protocol, new OnReportListener() {
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
        // 设置数据超时时间, 超过此时间如果终端没有回复数据则调用 onFailure 方法
        serialPortManager.setReceivedTimeout(350);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                while (true) {
                    serialPortManager.sendHexString("6A A6 01 07 01 01 00 E4 48 0D 0A", protocol, new OnReportListener() {
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
        // 多设备多协议
        final SerialPortProtocol protocol1 = new SerialPortProtocol();
        // 设置帧头
        protocol1.setFrameHeader((byte) 0x096, (byte) 0x69);
        protocol1.setDataLenIndex(2);   // 数据长度下标
        protocol1.setUselessLength(6);  // 除数据长度剩余长度, 不含帧头及数据本身, 因为此协议没有帧尾, 所以长度需要加上CRC的长度
        // 设置CRC计算方式和范围, 结束范围可为负值
        protocol1.setCRC(SerialPortProtocol.CRC_MODEL.CHECKSUM, 2, -2);
        // 启用协议
        // serialPortManager.setProtocol(protocol1);
        // 设置数据超时时间, 超过此时间如果终端没有回复数据则调用 onFailure 方法
        // serialPortManager.setReceivedTimeout(350);
        // 协议2
        final SerialPortProtocol protocol2 = new SerialPortProtocol();
        protocol2.setFrameHeader((byte) 0xAA);
        protocol2.setFrameEnd((byte) 0x55);
        //
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                for (int i = 0; i < 50; i++) {
                    final int finalI = i;
                    serialPortManager.sendHexString("96 69 01 FE 01 C0 05 08 32 FE", protocol1, new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes) {
                            Log.i(TAG, "i=" + finalI);
                        }

                        @Override
                        public void onFailure(SerialPortError error) {
                            Log.e(TAG, "i=" + finalI + ":" + error.toString());
                        }
                    });
                    serialPortManager.sendHexString("AA 01 02 00 00 00 03 55", protocol2, new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes) {
                            Log.i(TAG, "j=" + finalI);
                        }

                        @Override
                        public void onFailure(SerialPortError error) {
                            Log.e(TAG, "j=" + finalI + ":" + error.toString());
                        }
                    });
                }
                Log.i(TAG, "执行 for 完成");
            }
        }, 2000);
    }

    private void model6() {
        protocol.setFrameHeader((byte) 0x80, (byte) 0x03);
        protocol.setDataLenIndex(2);
        protocol.setUselessLength(2);
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.MODBUS_16_RTU, 0, -2);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                while (true) {
                    serialPortManager.sendHexString("80 03 00 00 00 02 DA 1A", protocol, new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes) {
                            Log.i(TAG, BytesUtil.toHexString(bytes));
                        }
                    });
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, 1000);
    }
}