package com.tim.serialport;

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

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    SerialPortManager  serialPortManager = new SerialPortManager();
    SerialPortProtocol protocol          = new SerialPortProtocol();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        test1();
    }

    private void test1() {
        // 设置帧头
        protocol.setFrameHeader((byte) 0x09C, (byte) 0xC9);
        // 设置帧尾
        protocol.setFrameEnd((byte) 0x0E, (byte) 0x0A);
        // 设置CRC计算方式和范围, 结束范围可为负值, 解决内容长度可变问题
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.MODBUS_16, 2, -4);
        // 启用协议
        serialPortManager.setProtocol(protocol);
        // 粘包时间设置, 单位:毫秒,
        // setProtocol 后该功能在setOnDataListener不生效.因为不再以超时判断而是以协议判断
        // 设置数据超时时间, 超过此时间如果终端没有回复数据则调用 onFailure 方法
        serialPortManager.setReceivedTimeout(300);
        serialPortManager.setOnDataListener(new OnDataListener() {
            @Override
            public void onDataReceived(byte[] bytes) {
                // 自动粘包, bytes内的数据仅是CRC计算的内容, 不返回帧头帧尾和CRC部分
            }

            @Override
            public void onDataSend(byte[] bytes) {
            }
        });
        serialPortManager.open("/dev/ttyS0", 115200);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                for (int i = 0; i < 400; i++) {
                    serialPortManager.sendHexString("6A A6 01 07 01 01 00 E4 48 0D 0A", new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes) {
                            Log.i("onSuccess", BytesUtil.toHexString(bytes));
                        }

                        @Override
                        public void onFailure(SerialPortError error) {
                            Log.i("onFailure", error.toString());
                        }

                        @Override
                        public void onComplete() {
                            super.onComplete();
                        }
                    });
                }
            }
        }, 1000);
    }
}