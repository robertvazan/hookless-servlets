// Part of Hookless Servlets: https://hookless.machinezoo.com
package com.machinezoo.hookless.servlets;

import java.nio.*;
import java.util.*;
import javax.servlet.http.*;

/*
 * Reactive response may be produced multiple times, several times as a draft and once as a non-draft result.
 * It must be therefore completely stateless, detached from servlet container, a pure data object,
 * which most importantly means that data has to be returned whole in a buffer instead of being streamed.
 * 
 * Contrary to reactive request, we could derive reactive response from HttpServletResponse,
 * because HttpServletResponse doesn't have any special features like HttpServletRequest.
 * We will however keep the response object consistent from request object and avoid inheritance.
 * Aside from consistency, it has the advantage of simpler API and cleaner implementation.
 * The only special feature lost from HttpServletResponse is adding session IDs to URLs.
 */
public class ReactiveServletResponse {
	/*
	 * Just like with the request, HttpServletResponse offers rich API full of convenience methods.
	 * Like with request, we will reduce the API to the bare minimum:
	 * - headers as a Map
	 * - output as a byte buffer
	 * 
	 * Constructor creates valid, empty 200 response, which simplifies app code and tests.
	 * Headers and cookies are initialized to empty mutable collections so that app can just add items.
	 * 
	 * Just like in reactive request, we provide case-insensitive header map and dedicated cookie API.
	 */
	private int status = HttpServletResponse.SC_OK;
	public int status() {
		return status;
	}
	public ReactiveServletResponse status(int status) {
		this.status = status;
		return this;
	}
	private Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	public Map<String, String> headers() {
		return headers;
	}
	public ReactiveServletResponse headers(Map<String, String> headers) {
		Objects.requireNonNull(headers);
		this.headers = headers;
		return this;
	}
	private List<Cookie> cookies = new ArrayList<>();
	public List<Cookie> cookies() {
		return cookies;
	}
	public ReactiveServletResponse cookies(List<Cookie> cookies) {
		Objects.requireNonNull(cookies);
		this.cookies = cookies;
		return this;
	}
	/*
	 * We have a choice of what type to use for output buffer: byte[], ByteBuffer, or streams/channels.
	 * Streams/channels can be either in push mode (output stream/channel) or in pull mode (input stream/channel).
	 * Pull mode streams/channels are dangerous, because apps don't necessarily handle async streaming correctly.
	 * Push mode channels enforce duplication of data for servlets that always return the same response.
	 * 
	 * Byte arrays and ByteBuffer instances both support buffer sharing among requests for the same resource.
	 * Of these two, ByteBuffer is more flexible. It doesn't even have to be in memory.
	 * It could be a memory-mapped file, section of a larger buffer (for range requests),
	 * or an entirely custom implementation that suits the application.
	 * 
	 * The only downside to ByteBuffer is that it contains mutable state (mark, position, limit).
	 * Reactive servlet calls duplicate() on the ByteBuffer to avoid damaging the original buffer.
	 * 
	 * Reactive servlet assumes that the response body lies between buffer's position and limit.
	 * This is the standard way to indicate which part of the buffer contains the data.
	 * When the buffer is created by wrapping byte[], position and limit are already set correctly.
	 * If the application writes data into preallocated buffer, it has to call flip() when done.
	 */
	private ByteBuffer data = ByteBuffer.allocate(0);
	public ByteBuffer data() {
		return data;
	}
	public ReactiveServletResponse data(ByteBuffer data) {
		Objects.requireNonNull(data);
		this.data = data;
		return this;
	}
}
