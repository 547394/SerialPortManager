package com.tim.serialportlib;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 本地单元测试（运行在开发机的 JVM 上），用于验证测试框架本身是否可用。
 * <p>
 * 由 Android Studio 新建模块时自动生成。库内的纯函数（如 {@link BytesUtil}、{@link Crypto}）
 * 不依赖 Android 运行时，可以直接在这里做单元测试。
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