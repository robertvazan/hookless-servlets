// Part of Hookless Servlets: https://hookless.machinezoo.com/servlets
package com.machinezoo.hookless.servlets;

import java.net.*;
import java.util.*;
import javax.servlet.http.*;
import com.machinezoo.noexception.*;
import com.machinezoo.stagean.*;

/*
 * Requests are only produced by reactive servlets. There's no reason why app would want to modify them.
 * We nevertheless provide public setters to support unit tests and perhaps other unanticipated use cases.
 * 
 * Contrary to reactive servlet, reactive request does not derive from corresponding HttpServletRequest.
 * This is partly because HttpServletRequest has to be wrapped in order to be extended.
 * Such wrapper would be very complicated and tricky to implement.
 * This is a controversial decision, because we are discarding important functionality of servlet requests,
 * notably sessions, authentication, and access to servlet container and its functionality.
 * 
 * Nevertheless, we want reactive request to have some properties that are incompatible with HttpServletRequest.
 * We need app code to treat the request as immutable, so that we can call service() method repeatedly
 * with the same request in case previous invocations signaled reactive blocking.
 * We also want to let the application retain reference to the request long after it has been serviced.
 * For these reasons, we want reactive request to be pure data, which is impossible with HttpServletRequest.
 */
/**
 * Reactive alternative to {@link HttpServletRequest} used in {@link ReactiveServlet}.
 */
@StubDocs
@DraftTests("see coverage")
public class ReactiveServletRequest {
	public ReactiveServletRequest() {
	}
	/*
	 * HttpServletRequest exposes rich API with lots of convenience methods.
	 * I believe this results in an API that is both overwhelming and poor in functionality.
	 * Reactive request instead exposes minimal API and expects the app
	 * to use 3rd party libraries for convenience and additional functionality.
	 * 
	 * Specifically, the following APIs were simplified:
	 * - local/remote socket is exposed as single InetSocketAddress
	 * - various URL components are consolidated into single URL property
	 * - headers are exposed only as a Map
	 * 
	 * Reactive servlet initializes all request properties to something non-null. This simplifies application code.
	 * Default constructor provides reasonable defaults for everything except URL, which simplifies unit tests.
	 */
	private InetSocketAddress local = new InetSocketAddress(0);
	public InetSocketAddress local() {
		return local;
	}
	public ReactiveServletRequest local(InetSocketAddress local) {
		this.local = local;
		return this;
	}
	private InetSocketAddress remote = new InetSocketAddress(0);
	public InetSocketAddress remote() {
		return remote;
	}
	public ReactiveServletRequest remote(InetSocketAddress remote) {
		this.remote = remote;
		return this;
	}
	private String method = "GET";
	public String method() {
		return method;
	}
	public ReactiveServletRequest method(String method) {
		this.method = method;
		return this;
	}
	private String url;
	public String url() {
		return url;
	}
	public ReactiveServletRequest url(String url) {
		this.url = url;
		return this;
	}
	/*
	 * We provide default Map with case-insensitive key comparison as a convenience
	 * to make it easy to set headers regardless of whether they are
	 * in HTTP/2 lower-case or HTTP/1.1 Pascal-Case.
	 */
	private Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	public Map<String, String> headers() {
		return headers;
	}
	public ReactiveServletRequest headers(Map<String, String> headers) {
		Objects.requireNonNull(headers);
		this.headers = headers;
		return this;
	}
	/*
	 * Cookies are an exception to our rule of keeping minimal API.
	 * Technically, they can be also represented as part of headers.
	 * Cookies are however complex enough and used frequently enough to warrant dedicated API.
	 * Unlike say URLs, cookies don't have good 3rd party parsing/formatting libraries.
	 */
	private List<Cookie> cookies = new ArrayList<>();
	public List<Cookie> cookies() {
		return cookies;
	}
	public ReactiveServletRequest cookies(List<Cookie> cookies) {
		Objects.requireNonNull(cookies);
		this.cookies = cookies;
		return this;
	}
	/*
	 * In order to make reactive request into pure data structure,
	 * we have to replace HttpServletRequest input stream with data buffer.
	 * This means some extra memory usage, but it is inevitable
	 * if we want reactivity and framework-handled async request reader.
	 * 
	 * We have a choice of how to expose the buffer: byte[], ByteBuffer, InputStream.
	 * ByteBuffer has internal state that is way too easy to modify during request processing.
	 * That would either result in lots of code with reactivity bugs
	 * or it would force us to clone the whole request every time.
	 * Even when cloned, app code might attempt to consume the ByteBuffer several times.
	 * InputStream has issues similar to ByteBuffer in addition to being hard to use.
	 * It is safer to provide the app with simple byte array, which doesn't carry any state.
	 * App code can always wrap the byte array in ByteBuffer or ByteArrayInputStream.
	 */
	private byte[] data = new byte[0];
	public byte[] data() {
		return data;
	}
	public ReactiveServletRequest data(byte[] data) {
		Objects.requireNonNull(data);
		this.data = data;
		return this;
	}
	/*
	 * We are exposing public construct that converts existing HttpServletRequest.
	 * This constructor cannot read request body, but it can convert all other information.
	 * This constructor is used by reactive servlet, but it might be useful in application code or tests too.
	 */
	public ReactiveServletRequest(HttpServletRequest request) {
		local = parseAddress(request.getLocalAddr(), request.getLocalPort());
		remote = parseAddress(request.getRemoteAddr(), request.getRemotePort());
		method = request.getMethod();
		/*
		 * HttpServletRequest provides numerous convenience methods for obtaining parts of the request URL,
		 * but entertainingly, it doesn't provide any method that would just return the whole unmangled URL.
		 * The closest we can get is getRequestURL(), which returns everything except the query string.
		 * So we just append the query string to it and hope this is the most accurate URL we can construct.
		 */
		StringBuffer address = request.getRequestURL();
		String query = request.getQueryString();
		if (query != null) {
			address.append('?');
			address.append(query);
		}
		url = address.toString();
		/*
		 * Sometimes an invalid or denormalized URL sneaks past the servlet container and the front-end web server.
		 * We will construct an URI instance here to ensure the URL can be parsed.
		 * URI constructor will throw if the URL cannot be parsed.
		 * We will also call URI's normalize() method to make the URL as consistent as possible.
		 */
		url = Exceptions.sneak().get(() -> new URI(url).normalize().toString());
		/*
		 * HTTP specification allows us to join duplicate headers into comma-separated list.
		 * Such joined header should be equivalent to a list of duplicate headers.
		 * We perform the joining here in order to have nice Map interface to headers.
		 */
		for (String name : Collections.list(request.getHeaderNames()))
			headers.put(name, String.join(", ", Collections.list(request.getHeaders(name))));
		if (request.getCookies() != null)
			cookies.addAll(Arrays.asList(request.getCookies()));
	}
	private static InetSocketAddress parseAddress(String serialized, int port) {
		if (serialized == null)
			return new InetSocketAddress(port);
		try {
			InetAddress ip = InetAddress.getByName(serialized);
			return new InetSocketAddress(ip, port);
		} catch (Throwable ex) {
			return new InetSocketAddress(port);
		}
	}
}
