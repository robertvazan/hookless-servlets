// Part of Hookless Servlets: https://hookless.machinezoo.com
package com.machinezoo.hookless.servlets;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

public class ReactiveServletResponseTest {
	@Test
	public void caseInsensitive() {
		ReactiveServletResponse r = new ReactiveServletResponse();
		r.headers().put("HEADER", "value");
		assertEquals("value", r.headers().get("header"));
	}
}
