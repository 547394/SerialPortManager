# SerialPortManager

Android 串口通讯库：**一条一条发，一条一条等**，按协议自动切帧、校验、丢弃干扰字节。

支持发送回调、并发排队、自定义协议、CRC 校验、自动粘包、自动去除前导与尾随干扰数据，可在同一串口上交替使用多种设备协议。

底层串口读写基于 [Android-SerialPort](https://github.com/F1ReKing/Android-SerialPort)（`com.github.F1ReKing:Android-SerialPort:1.6.0`）二次封装。

[![](https://jitpack.io/v/547394/SerialPortManager.svg)](https://jitpack.io/#547394/SerialPortManager)

## 特性

- **命令队列**：所有需要应答的命令进入同一 FIFO 队列，发一条、等一条，前一条结束才发下一条
- **三种切帧方式**：固定长度、帧头 + 帧尾、帧头 + 长度字段
- **四种校验**：BCC、累加和、Modbus CRC16、Modbus CRC16（RTU 字节序）
- **抗干扰**：帧头之前允许多余字节（自动左移重同步），帧尾之后允许少量干扰字节（配置校验时自动截断）
- **多设备多协议**：每条命令携带自己的协议，队列串行执行，互不干扰
- **不静默失败**：发送失败、空数据、校验错误、超时、缓冲区溢出都会以 `onFailure` 收尾，不会让调用方永远等下去

## 引入

**Step 1.** 在根 `build.gradle` 中加入 JitPack 仓库：

```gradle
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

**Step 2.** 添加依赖：

```gradle
dependencies {
    implementation 'com.github.547394:SerialPortManager:1.0.10'
}
```

> 需要引入尚未发版的提交时，把版本号换成 commit 短哈希即可，例如 `com.github.547394:SerialPortManager:1a2b3c4`。

最低支持 Android 4.4（API 19），编译版本 API 28。

## 快速开始

```java
SerialPortManager serialPortManager = new SerialPortManager();

// 串口打开结果，主线程回调
serialPortManager.setOnOpenListener(new OnOpenListener() {
    @Override
    public void onSuccess(File device) {
        Log.i(TAG, "已打开: " + device.getPath());
    }

    @Override
    public void onFailure(File device, SerialPortError error) {
        Log.e(TAG, "打开失败: " + error);
    }
});

// 全局数据监听，不区分命令
serialPortManager.setOnDataListener(new OnDataListener() {
    @Override
    public void onDataReceived(byte[] bytes) {
        // 原始整帧，含帧头、帧尾、校验位
        Log.i(TAG, BytesUtil.toHexString(bytes));
    }
});

// 应答超时，同时也是无协议时的粘包间隔，单位毫秒
serialPortManager.setReceivedTimeout(300);

// 8 数据位、无校验、1 停止位
serialPortManager.open("/dev/ttyS0", 115200);

// 页面销毁时释放线程与串口
// serialPortManager.close();
```

`open()` 没有返回值，打开失败也不会抛异常，结果一律以 `OnOpenListener` 为准（回调抛送到主线程，可能晚于 `open()` 返回）。打开后、下发第一条命令之前，总线上到达的字节会被丢弃，避免把上一次通信的残留当成应答。

## 协议配置

`SerialPortProtocol` 描述“怎样才算收到一帧”以及“怎样校验这一帧”。

### 切帧方式

| 场景 | 配置 |
|---|---|
| 固定长度（无帧头帧尾） | `setFixedLength(20)` |
| 帧头 + 帧尾 | `setFrameHeader(...)` + `setFrameEnd(...)` |
| 帧头 + 长度字段（无帧尾） | `setFrameHeader(...)` + `setDataLenIndex(2)` + `setUselessLength(6)` |
| 无协议（超时粘包） | 不配置任何内容，靠 `setReceivedTimeout()` 静默间隔切分 |

```java
SerialPortProtocol protocol = new SerialPortProtocol();

// 帧头、帧尾都支持多字节
protocol.setFrameHeader((byte) 0x6A, (byte) 0xA6);
protocol.setFrameEnd((byte) 0x0D, (byte) 0x0A);

// 校验方式 + 校验范围 [start, end)，end 为负数表示从帧尾倒数
protocol.setCRC(SerialPortProtocol.CRC_MODEL.MODBUS_16, 2, -4);
```

### 校验范围

`setCRC(model, start, end)` 的区间是**左闭右开** `[start, end)`：

- `start`：帧内起始下标（含）
- `end`：结束位置（不含）。正数表示绝对下标，**负数表示从帧尾往前数的偏移**

例如帧尾占 2 字节、校验位占 2 字节时，`setCRC(MODBUS_16, 2, -4)` 表示从第 2 字节算到倒数第 4 字节。

校验位固定在帧尾之前：`BCC` 占 1 字节，其余占 2 字节。`MODBUS_16` 按高字节在前比较，`MODBUS_16_RTU` 按低字节在前比较。

### 长度字段

长度字段为**单字节**，表示数据区字节数：

```java
SerialPortProtocol protocol = new SerialPortProtocol();
protocol.setFrameHeader((byte) 0x96, (byte) 0x69);
protocol.setDataLenIndex(2);   // 长度字段位于下标 2
protocol.setUselessLength(6);  // 除数据区、帧头、帧尾、校验位之外的固定字节数
protocol.setCRC(SerialPortProtocol.CRC_MODEL.CHECKSUM, 2, -2);
```

- `dataLenIndex = 0` 表示没有长度字段
- `uselessLength` 用于“长度字段只统计数据区，但帧里还有命令字/地址”的协议
- 每帧结束后长度字段会自动复位，不同帧长可以交替出现

## 下发命令

```java
serialPortManager.sendHexString(
        "6A A6 01 07 01 01 00 E4 48 0D 0A",
        protocol,
        1,                       // flag，随回调原样返回
        new OnReportListener() {
            @Override
            public void onSuccess(byte[] bytes, int flag) {
                Log.i(TAG, "flag=" + flag + " " + BytesUtil.toHexString(bytes));
            }

            @Override
            public void onFailure(SerialPortError error, int flag) {
                Log.e(TAG, "flag=" + flag + " " + error);
            }

            @Override
            public void onComplete() {
                // 无论成功失败都会走到这里，适合发下一条
            }
        });
```

### 队列语义

- 命令按入队顺序**逐条下发**：成功、失败或超时之后，才发下一条
- 两条命令之间默认间隔 50 ms，可用 `setSendInterval(int)` 调整
- `flag` 用于在同一个监听器实例上区分不同命令
- 回调序列固定为 `onSuccess -> onComplete` 或 `onFailure -> onComplete`
- 调用 `close()` 会丢弃队列中未完成的命令，**这些命令不会收到任何回调**

不需要应答的场景可以直接发送，不经过队列、不触发超时与解析：

```java
serialPortManager.sendHexString("AA 55 01 02");
```

## 回调与线程

| 回调 | 线程 |
|---|---|
| `OnOpenListener.onSuccess` / `onFailure` | 主线程 |
| `OnDataListener.onDataSend` | 底层发送线程 |
| `OnDataListener.onDataReceived` | 底层接收线程或工作线程 |
| `OnReportListener.onSuccess` / `onFailure` / `onComplete` | 底层接收线程或工作线程 |

除打开回调外，**其余回调都不在主线程**，操作 UI 请自行切换到主线程。回调中抛出的异常会中断串口工作线程，请务必在实现内部捕获。

## 错误码

| 取值 | 含义 |
|---|---|
| `RECEIVED_TIMEOUT` | 命令已下发，超时时间内没有收到符合协议的完整帧 |
| `RECEIVED_CRC_ERROR` | 收到了完整帧，但校验不通过 |
| `ACHIEVE_ERROR` | 兜底错误：串口未打开、发送数据为空、底层拒发、缓冲区溢出、打开失败 |

## 常用 API

| 方法 | 说明 |
|---|---|
| `open(port, baudRate)` | 以 8N1 打开串口 |
| `open(port, baudRate, dataBits, parity, stopBits)` | 指定串口参数打开 |
| `close()` | 关闭串口，清空队列，结束工作线程；之后可再次 `open()` |
| `isOpen()` | 串口是否已打开 |
| `getAllDevicesPath()` | 列出系统串口设备路径 |
| `setProtocol(protocol)` | 设置默认接收协议 |
| `setOnDataListener(listener)` | 设置全局数据监听 |
| `setOnOpenListener(listener)` | 设置打开结果监听 |
| `setReceivedTimeout(ms)` | 应答超时 / 无协议时的粘包间隔，默认 350 ms |
| `setSendInterval(ms)` | 相邻命令间隔，默认 50 ms |
| `setBufferSize(bytes)` | 接收缓冲区大小，默认 2048 字节 |
| `enableDebug(boolean)` | 打开/关闭调试日志 |
| `sendBytes(byte[])` / `sendHexString(String)` | 直发，不经过队列 |
| `sendBytes(byte[], protocol, flag, listener)` | 入队下发，等待应答 |
| `sendHexString(String, protocol, flag, listener)` | 同上，十六进制字符串形式 |

## 已知限制

1. **无校验时不做尾随干扰截断**。没有 CRC 就无法区分“数据区里的帧尾字节”和“真正的帧尾”，贸然截断会把好帧切坏，因此此时只认末尾的帧尾。若设备帧尾后常带干扰，建议启用校验，或改用固定长度 / 长度字段协议。
2. **前导干扰中包含帧头字节时可能重同步失败**。例如帧头是 `0x02`、干扰是 `02 99`，会以错误的起点解析，该帧按校验失败或超时处理。
3. **不支持断线自动重连**。底层接收线程在 `read()` 出错时会直接退出，此时 `isOpen()` 仍可能返回 `true`；确认异常后应先 `close()` 再 `open()`。
4. **底层串口参数是静态字段**。同一进程内多串口并发 `open()` 可能互相覆盖波特率等配置；需要多串口时建议串行打开，或 fork `Android-SerialPort`。
5. **不是主线程回调**，见上一节。

## 示例

完整示例见 [MainActivity.java](app/src/main/java/com/tim/serialport/MainActivity.java)，包含固定长度、帧头帧尾、长度字段、多协议交替等配置。

## 行为说明

当前版本的解析与队列行为（相对早期版本）：

- 发送数据为空、底层拒发、工作线程已结束，都会立即回调 `ACHIEVE_ERROR`，不再让队列永久卡住
- 变长协议每帧结束后复位长度字段，不同帧长的帧可以连续收到
- `close()` 之后可以立即 `open()`，工作线程会重建
- 帧尾之后、下一次 read 才到达的多余字节会被丢弃；同一次 read 内的尾随干扰，在配置了校验时会被截断
- 超时任务绑定到具体命令，迟到的超时不会误伤后续命令
- 接收缓冲区溢出、校验失败都会回调，不再静默停住
