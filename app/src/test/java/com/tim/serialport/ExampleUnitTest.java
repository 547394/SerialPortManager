package com.tim.serialport;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 本地单元测试（运行在开发机的 JVM 上），用于验证测试框架本身是否可用。
 * <p>
 * 由 Android Studio 新建模块时自动生成；串口相关的逻辑建议放在
 * {@link com.tim.serialportlib} 的纯函数中，便于在这里直接测试。
 */
public class ExampleUnitTest {

    /**
     * 一条必然成立的断言，用于确认测试框架已正确接入。
     */
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }
}