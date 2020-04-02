// Part of Hookless Servlets: https://hookless.machinezoo.com
package com.machinezoo.hookless.servlets;

import static java.util.stream.Collectors.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import javax.servlet.http.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.utils.*;

/*
 * Reactive servlet is a comfy wrapper around the incredibly messy async servlet API.
 * All the app has to do is to supply a single method that may possibly reactively block.
 * Reactive servlet will wait for the first non-draft response and return that to the client.
 * It will do all that without blocking any threads. It also handles non-blocking reads and writes.
 * 
 * We are deriving from HttpServlet, which has the downside of importing all the servlet cruft,
 * but the advantage is that reactive servlet can be passed wherever HttpServlet is expected,
 * it can be configured in XML or via annotations, and all the container-provided HttpServlet methods are accessible.
 * Apps also get a chance to hook into any low-level servlet functionality if they find a reason to do so.
 */
@SuppressWarnings("serial") public abstract class ReactiveServlet extends HttpServlet {
	public ReactiveServlet() {
		OwnerTrace.of(this)
			.alias("servlet")
			.tag("classname", getClass().getSimpleName());
	}
	/*
	 * We will overload service() and doXXX() methods with our own reactive request and response.
	 * This will make the API very familiar to servlet developers.
	 * 
	 * We are however opting to have the response returned instead of passing it in as an output parameter.
	 * This is intended to encourage functional programming style that reconstructs data instead of modifying it.
	 * This functional API is aided by the fact that the response carries all data.
	 * We have thus no need for complicated output stream logic that would require the response to be an out parameter.
	 *
	 * Reactive requests and responses are so different from HttpServletRequest/Response
	 * that we are implementing them as root classes without inheriting from HttpServletRequest/Response.
	 * 
	 * We are making service() and all doXXX() methods public to facilitate unit testing
	 * on reactive request/response level without having to simulate the whole servlet container.
	 * Direct unit testing is further facilitated by pure data nature of reactive request/response
	 * and reasonable defaults for reactive requests.
	 * 
	 * Just like with normal servlets, doXXX methods default to returning 405 Method Not Allowed except for HEAD and OPTIONS.
	 * HttpServlet also provides TRACE implementation, but we choose not to for security reasons.
	 */
	private static ReactiveServletResponse disallowed = new ReactiveServletResponse()
		.status(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	static {
		/*
		 * HttpServlet by default sends 405 Method Not Allowed and other errors with caching enabled.
		 * We add Cache-Control header by default to ensure errors aren't cached.
		 */
		disallowed.headers().put("Cache-Control", "no-cache, no-store");
	}
	public ReactiveServletResponse service(ReactiveServletRequest request) {
		switch (request.method()) {
		case "GET":
			return doGet(request);
		case "HEAD":
			return doHead(request);
		case "POST":
			return doPost(request);
		case "PUT":
			return doPut(request);
		case "DELETE":
			return doDelete(request);
		case "OPTIONS":
			return doOptions(request);
		case "TRACE":
			return doTrace(request);
		default:
			/*
			 * HttpServlet returns 501 Not Implemented instead of 405 Method Not Allowed here.
			 * I think it is more consistent to return 405 for unknown methods.
			 * It also makes it clear to the client that the problem is in the HTTP method.
			 */
			return disallowed;
		}
	}
	public ReactiveServletResponse doGet(ReactiveServletRequest request) {
		return disallowed;
	}
	public ReactiveServletResponse doPost(ReactiveServletRequest request) {
		return disallowed;
	}
	public ReactiveServletResponse doPut(ReactiveServletRequest request) {
		return disallowed;
	}
	public ReactiveServletResponse doDelete(ReactiveServletRequest request) {
		return disallowed;
	}
	public ReactiveServletResponse doTrace(ReactiveServletRequest request) {
		return disallowed;
	}
	public ReactiveServletResponse doHead(ReactiveServletRequest request) {
		/*
		 * Just like in HttpServlet, HEAD defaults to returning GET without the body.
		 */
		ReactiveServletResponse response = doGet(request);
		response.data(ByteBuffer.allocate(0));
		return response;
	}
	public ReactiveServletResponse doOptions(ReactiveServletRequest request) {
		/*
		 * Just like in HttpServlet, we return the list of supported HTTP methods
		 * by scanning this class for implementations of doXXX methods.
		 * 
		 * OPTIONS must be included as otherwise this method wouldn't be executed.
		 */
		Set<String> methods = new HashSet<>(Arrays.asList("OPTIONS"));
		for (Class<?> clazz = getClass(); clazz != ReactiveServlet.class; clazz = clazz.getSuperclass()) {
			for (Method method : clazz.getDeclaredMethods()) {
				switch (method.getName()) {
				case "doGet":
					/*
					 * If GET is implemented, then our default HEAD implementation will work too.
					 */
					methods.add("GET");
					methods.add("HEAD");
					break;
				case "doHead":
					/*
					 * HEAD can be also implemented on its own even when GET isn't.
					 * This scenario is very unlikely though. We support it for completeness.
					 */
					methods.add("HEAD");
					break;
				case "doPost":
					methods.add("POST");
					break;
				case "doPut":
					methods.add("PUT");
					break;
				case "doDelete":
					methods.add("DELETE");
					break;
				case "doTrace":
					/*
					 * Contrary to HttpServlet, we don't implement TRACE method.
					 * We will however report it as supported if application implements it.
					 */
					methods.add("TRACE");
					break;
				}
			}
		}
		/*
		 * If application wishes to add more headers to OPTIONS response,
		 * it can override this method, call super to get the defaults, and then add any headers it wants.
		 * 
		 * Our response by default includes Cache-Control to avoid surprisingly long caching.
		 */
		ReactiveServletResponse response = new ReactiveServletResponse();
		response.headers().put("Allow", methods.stream().sorted().collect(joining(", ")));
		response.headers().put("Cache-Control", "no-cache, no-store");
		return response;
	}
	/*
	 * All request handling is actually performed in ReactiveServletTask.
	 * We need to instantiate an object for every request,
	 * because reactive request handling might take some time and
	 * we need to store intermediate state somewhere meantime.
	 */
	@Override protected void service(HttpServletRequest request, HttpServletResponse response) {
		new ReactiveServletTask(ReactiveServlet.this, request, response).start();
	}
	/*
	 * We allow configuring custom executor for the reactive code.
	 * This is important for servlets that might do heavy processing or blocking,
	 * which is a poor fit for the main hookless thread pool.
	 * Networking code will still run on servlet container's thread pool.
	 * 
	 * We have a choice between fluent style and bean style getters/setters.
	 * Bean style is consistent with HttpServlet while fluent style
	 * is consistent with ReactiveServletRequest and ReactiveServletResponse.
	 * XML and other dynamic configuration is irrelevant,
	 * because ExecutorService is not something to be configured in XML.
	 * We are opting for fluent style to keep the whole reactive servlet API consistent.
	 */
	private Executor executor = ReactiveExecutor.instance();
	public Executor executor() {
		return executor;
	}
	public void executor(Executor executor) {
		this.executor = executor;
	}
	@Override public String toString() {
		return OwnerTrace.of(this).toString();
	}
}
