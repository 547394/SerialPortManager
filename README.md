# SerialPortManager
Android串口通讯, 支持发送数据回调, 支持并发处理, 自定义协议, CRC校验, 自动粘包, 自动去除冗余的干扰数据

是 [https://github.com/Geek8ug/Android-SerialPort] (Android-SerialPort) 项目的二次封装

## 引入

**Step 1.** Add the JitPack repository to your build file

Add it in your root build.gradle at the end of repositories:


	allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}


**Step 2.** Add the dependency


	dependencies {
	        implementation ''
	}


## 使用

### 1. 监听串口数据
```java
SerialPortManager serialPortManager = new SerialPortManager();
// 粘包时间设置, 单位:毫秒,
serialPortManager.setReceivedTimeout(100);
        
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

```

### 2. 增加协议和CRC校验
```java
// 自定义回复协议内容, 必须满足以下条件 onDataReceived 才会有回复
SerialPortProtocol protocol = new SerialPortProtocol();
// 设置帧头
protocol.setFrameHeader((byte) 0x09C, (byte) 0xC9);
// 设置帧尾
protocol.setFrameEnd((byte) 0x0E, (byte) 0x0A);
// 设置CRC计算方式和范围, 结束范围可为负值, 解决内容长度可变问题
protocol.setCRC(SerialPortProtocol.CRC_MODEL.MODBUS_16, 2, -4);
// 启用协议, setProtocol 后s etReceivedTimeout 无效, 因为不再以超时判断而是以协议判断
serialPortManager.setProtocol(protocol);
```

### 3.发送一条需要回复的串口指令
```java
// 设置数据超时时间, 超过此时间如果终端没有回复数据则调用 onFailure 方法
serialPortManager.setReceivedTimeout(300);
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
```

