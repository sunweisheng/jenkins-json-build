package com.bluersw.java.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HelloWorldTests {

	@Test
	void hello() {
		HelloWorld hw = new HelloWorld();
		String re = hw.hello("sws");
		assertEquals(re,"你好！sws,这是一个微服务。");
	}
}