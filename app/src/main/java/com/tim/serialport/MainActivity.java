package com.tim.serialport;

import android.os.Bundle;
import android.util.Log;

import com.tim.serialportlib.BytesUtil;
import com.tim.serialportlib.OnReportListener;
import com.tim.serialportlib.SerialPortError;
import com.tim.serialportlib.SerialPortManager;
import com.tim.serialportlib.SerialPortProtocol;

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
        protocol.setFrameHeader((byte) 0x09C, (byte) 0xC9);
        protocol.setFrameEnd((byte) 0x0E, (byte) 0x0A);
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.MODBUS_16, 2, -4);
        serialPortManager.setProtocol(protocol);
        serialPortManager.setReceivedTimeout(500);
        serialPortManager.open("/dev/ttyS0", 115200);
        serialPortManager.sendHexString("6A A6 01 07 01 01 00 E4 48 0D 0A", new OnReportListener() {
            @Override
            public void onSuccess(byte[] bytes) {
                Log.i("onReport", "onSuccess " + BytesUtil.toHexString(bytes));
            }

            @Override
            public void onFailure(SerialPortError error) {
                Log.i("onReport", "onFailure, " + error.toString());
            }

            @Override
            public void onComplete() {
                super.onComplete();
            }
        });
    }
}