// Part of Hookless Servlets: https://hookless.machinezoo.com/servlets
package com.machinezoo.hookless.servlets;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import javax.servlet.http.*;
import org.junit.jupiter.api.*;

public class ReactiveServletRequestTest {
	@Test
	public void convert() {
		HttpServletRequest hr = mock(HttpServletRequest.class);
		when(hr.getRemoteAddr()).thenReturn("12.34.56.78");
		when(hr.getRemotePort()).thenReturn(12345);
		when(hr.getLocalAddr()).thenReturn("192.168.0.33");
		when(hr.getLocalPort()).thenReturn(8080);
		when(hr.getMethod()).thenReturn("POST");
		when(hr.getRequestURL()).thenReturn(new StringBuffer("http://somplace/something"));
		when(hr.getQueryString()).thenReturn("k1=v1&k2=v2");
		when(hr.getHeaderNames()).thenReturn(Collections.enumeration(Arrays.asList("Header1", "Header2")));
		when(hr.getHeaders("Header1")).thenReturn(Collections.enumeration(Arrays.asList("value")));
		when(hr.getHeaders("Header2")).thenReturn(Collections.enumeration(Arrays.asList("value1", "value2")));
		when(hr.getCookies()).thenReturn(new Cookie[] { new Cookie("n", "v") });
		ReactiveServletRequest rq = new ReactiveServletRequest(hr);
		assertEquals("12.34.56.78", rq.remote().getHostString());
		assertEquals(12345, rq.remote().getPort());
		assertEquals("192.168.0.33", rq.local().getHostString());
		assertEquals(8080, rq.local().getPort());
		assertEquals("POST", rq.method());
		assertEquals("http://somplace/something?k1=v1&k2=v2", rq.url().toString());
		assertThat(rq.headers().keySet(), containsInAnyOrder("Header1", "Header2"));
		assertEquals("value", rq.headers().get("header1"));
		assertEquals("value1, value2", rq.headers().get("header2"));
		assertEquals(1, rq.cookies().size());
		assertEquals("n", rq.cookies().get(0).getName());
		assertEquals("v", rq.cookies().get(0).getValue());
	}
	@Test
	public void caseInsensitive() {
		ReactiveServletRequest rq = new ReactiveServletRequest();
		rq.headers().put("HEADER", "value");
		assertEquals("value", rq.headers().get("header"));
	}
}
