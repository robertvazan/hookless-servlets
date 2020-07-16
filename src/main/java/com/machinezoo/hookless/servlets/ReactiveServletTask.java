// Part of Hookless Servlets: https://hookless.machinezoo.com
package com.machinezoo.hookless.servlets;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.apache.commons.collections4.*;
import org.apache.commons.collections4.map.*;
import org.slf4j.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.noexception.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

/*
 * Servlet async API is a madness filled with race rules. We need some hard rules to keep this mess under control.
 * Reactive servlet is implemented as a state machine where every state transition is synchronized and non-blocking.
 * 
 * App exceptions cause nice 500 HTTP errors, but any other kind of exception just kills the whole async transaction.
 * This is done by calling AsyncContext.complete() and letting servlet container deal with it somehow.
 * Once AsyncContext.complete() is called for whatever reason, all activity in the reactive servlet ceases.
 * 
 * We assume that callbacks aren't invoked immediately when they are set,
 * so we won't face surprising recursion on the path reactive servlet -> servlet container -> reactive servlet.
 * This is a big assumption, but defending against recursion would result in crazily complicated code.
 * 
 * Timeouts (30s by default) will result in nice 504 HTTP errors. They are our last line of defense against errors.
 * 
 * Callbacks invoked by servlet container (for example socket writability) are executed on container's thread pool.
 * The same is true of the startup code called from servlet's service() method, which runs in container's thread pool.
 * Evaluation of app-supplied reactive code runs on common thread pool where all hookless code runs by default.
 * When reactive factory finishes, it triggers short callback on hookless thread that schedules handler on container's pool.
 */
class ReactiveServletTask {
	private static final Logger logger = LoggerFactory.getLogger(ReactiveServletTask.class);
	/*
	 * Constructor is separated from start() method for cosmetic reasons.
	 * Only three parameters define the whole async transaction.
	 * Initiation and completion of AsyncContext is handled entirely by this class.
	 */
	private final ReactiveServlet servlet;
	private final HttpServletRequest request;
	private final HttpServletResponse response;
	ReactiveServletTask(ReactiveServlet servlet, HttpServletRequest request, HttpServletResponse response) {
		this.servlet = servlet;
		this.request = request;
		this.response = response;
		OwnerTrace.of(this).parent(servlet);
	}
	/*
	 * We will be tracking execution time using both short and long timer.
	 * Long timer has the advantage of showing the number and delay of currently running tasks.
	 * Short timer's advantage is tracking historical data including task count and their total and maximum time.
	 */
	private static final LongTaskTimer activeTasks = LongTaskTimer
		.builder("hookless.servlet.active")
		.register(Metrics.globalRegistry);
	private LongTaskTimer.Sample activeSample;
	private static final Timer timer = Metrics.timer("hookless.servlet.task");
	private Timer.Sample timerSample;
	/*
	 * Rule #1 of keeping the event maze under control is quick death after any error.
	 * The following method causes instant death of current request.
	 * Once 'completed' flag is set, nothing else will run.
	 * Async context completion is also performed at the end of normal request processing.
	 */
	private AsyncContext async;
	private boolean completed;
	private void complete() {
		if (!completed) {
			logger.trace("Completing async context.");
			completed = true;
			Exceptions.log(logger).run(async::complete);
			if (activeSample != null)
				activeSample.stop();
			if (timerSample != null)
				timerSample.stop(timer);
		}
	}
	/*
	 * Many code sections run under exception watch.
	 * If they throw any exception, usually from container-provided I/O methods, the exception must kill the request.
	 * Code like 'guard("...").run(() -> { ... });' must be wrapped around any code that may throw exceptions.
	 */
	private static final Counter exceptionsContainer = Metrics.counter("hookless.servlet.exceptions.container");
	private ExceptionHandler guard(String message) {
		return new ExceptionHandler() {
			@Override
			public boolean handle(Throwable exception) {
				logger.debug(message, exception);
				complete();
				exceptionsContainer.increment();
				return true;
			}
		};
	}
	/*
	 * Many exceptions happen asynchronously and the servlet container merely informs us about them via error events.
	 * All such error events execute the method below, which immediately terminates all request processing.
	 */
	private static final Counter exceptionsAsync = Metrics.counter("hookless.servlet.exceptions.async");
	private synchronized void die(Throwable exception) {
		logger.debug("Asynchronous exception was thrown.", exception);
		/*
		 * While AsyncContext.complete() is enough to stop all servlet container activity,
		 * reactive factory producing the response might be completely unaware of it
		 * and we have to cancel it explicitly here.
		 */
		cancel();
		complete();
		exceptionsAsync.increment();
	}
	/*
	 * Method start() is called immediately after the constructor.
	 * Its separation from the constructor is a cosmetic detail.
	 * This code runs synchronously in the servlet handler.
	 */
	synchronized void start() {
		logger.trace("Starting");
		timerSample = Timer.start(Clock.SYSTEM);
		/*
		 * We have to initialize AsyncContext before we do anything else.
		 * This is because nearly all code assumes that AsyncContext already exists.
		 */
		async = request.startAsync();
		guard("Failed to switch to async mode.").run(() -> {
			activeSample = activeTasks.start();
			async.addListener(new AsyncListener() {
				@Override
				public void onStartAsync(AsyncEvent event) throws IOException {
				}
				@Override
				public void onComplete(AsyncEvent event) throws IOException {
					/*
					 * Here we assume that completion events are either caused by our own code (calling AsyncContext.complete())
					 * or they are related to async errors or timeouts that are handled below.
					 * If this assumption is correct (fingers crossed), we can leave this event handler empty.
					 */
				}
				@Override
				public void onError(AsyncEvent event) throws IOException {
					logger.trace("Async context signals error.", event.getThrowable());
					die(event.getThrowable());
				}
				@Override
				public void onTimeout(AsyncEvent event) throws IOException {
					/*
					 * Theoretically, under extreme circumstances, timeout might occur before our event handler is registered.
					 * We would miss timeout event in that case. Servlet container would then complete the AsyncContext for us,
					 * but again that could be delayed under extreme circumstances and we might be running reactive factory by that time.
					 * This is not as bad as it looks though. Worst case scenario is reactive factory completing in vain
					 * and some code in this class subsequently throwing an exception from container-provided API.
					 * Since exceptions always kill the request, cleanup would happen at that moment.
					 */
					logger.trace("Async context signals timeout.");
					timeout();
				}
			});
		});
		parse();
	}
	/*
	 * From now on, we will be encountering situations (generally error conditions) where we need to send a response.
	 * This method contains some common code for sending responses. It sets a flag that prevents double response.
	 * This method has no synchronization, because it is expected to be executed from already synchronized methods.
	 */
	private boolean responded;
	private void respond(Runnable instructions) {
		logger.trace("Sending response.");
		responded = true;
		guard("Failed to send response.").run(instructions);
	}
	/*
	 * We will attempt to send nice 504 error in case we encounter timeout.
	 * If the container won't let us or we are already writing another response,
	 * we just ignore the timeout event and let the container deal with it.
	 * 
	 * We don't include any message for the user, because attempting async writing of response body
	 * under timeout conditions would likely fail and we don't want to ever block on writes.
	 * Application is free to add its own response body either via servlet filter or via HTTP server in reverse proxy mode.
	 */
	private static final Counter exceptionsTimeout = Metrics.counter("hookless.servlet.exceptions.timeout");
	private synchronized void timeout() {
		logger.trace("Timeout callback executed.");
		/*
		 * While AsyncContext.complete() is enough to stop all servlet container activity,
		 * reactive factory producing the response might be completely unaware of it
		 * and we have to cancel it explicitly here.
		 */
		cancel();
		/*
		 * Check 'responded' flag to make sure we don't send duplicate response.
		 */
		if (!responded && !completed) {
			respond(() -> {
				response.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
				/*
				 * HTTP permits caching error responses even though caches often refrain from caching them.
				 * We will add Cache-Control here to ensure the client retries the request.
				 */
				response.setHeader("Cache-Control", "no-cache, no-store");
			});
			if (rrequest != null)
				logger.warn("Timeout while processing request for {}.", rrequest.url());
			else
				logger.warn("Timeout while processing request.");
			complete();
		}
		exceptionsTimeout.increment();
	}
	/*
	 * Once we have AsyncContext, we can proceed to parse the request.
	 * This code still runs synchronously in the original servlet handler.
	 */
	private ReactiveServletRequest rrequest;
	private static final Set<String> countedMethods = new HashSet<>(Arrays.asList("GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE", "PATCH"));
	private static final Map<String, Counter> methodCounters = Collections.synchronizedMap(LazyMap.lazyMap(new HashMap<>(), new Transformer<String, Counter>() {
		@Override
		public Counter transform(String method) {
			return Metrics.counter("hookless.servlet.method", "method", countedMethods.contains(method) ? method : "OTHER");
		}
	}));
	private void parse() {
		if (!completed) {
			guard("Failed to parse request.").run(() -> {
				rrequest = new ReactiveServletRequest(request);
				OwnerTrace.of(this).tag("http.url", rrequest.url());
				logger.trace("Connection {} -> {}.", rrequest.remote(), rrequest.local());
				logger.trace("Requested {} {}.", rrequest.method(), rrequest.url());
				methodCounters.get(rrequest.method()).increment();
			});
			beginReading();
		}
	}
	/*
	 * Once we have the request header parsed, we can proceed to read request body.
	 * We take the effort to do this asynchronously since clients can be very slow in sending the request body.
	 * Request body is always read, even for GET and HEAD requests, where it simply yields zero-length result.
	 * Reading request body consists of some one-time setup code, then one or more invocations
	 * of the reading continuation, and then some finishing/cleanup code.
	 * Setup code below still runs synchronously in the original servlet handler.
	 */
	private ServletInputStream streamIn;
	private ByteArrayOutputStream dataIn;
	private void beginReading() {
		if (!completed) {
			guard("Failed to setup request body reading.").run(Exceptions.sneak().runnable(() -> {
				streamIn = request.getInputStream();
				/*
				 * We will be accumulating request body conveniently in ByteArrayOutputStream
				 * and then convert it to byte[] as required by ReactiveServletRequest.
				 */
				dataIn = new ByteArrayOutputStream();
				streamIn.setReadListener(new ReadListener() {
					@Override
					public void onDataAvailable() throws IOException {
						/*
						 * This event runs some time after ServletInputStream.isReady() returned false
						 * in the continuation below. We just restart the continuation in this case.
						 */
						logger.trace("Async reader signals data available.");
						continueReading();
					}
					@Override
					public void onAllDataRead() throws IOException {
						/*
						 * We don't differentiate between available data and EOF.
						 * We can thus handle this event identically to onDataAvailable() above.
						 */
						logger.trace("Async reader signals all data was read.");
						continueReading();
					}
					@Override
					public void onError(Throwable ex) {
						logger.trace("Async reader signals error.", ex);
						die(ex);
					}
				});
			}));
			/*
			 * We will run the first continuation synchronously.
			 * It is faster this way, because servlet container might already have the whole request body,
			 * in which case we would just read it all synchronously in continuation's loop.
			 * But we are also required to do it this way, because servlet container doesn't execute the continuation
			 * unless ServletInputStream.isReady() returned false before.
			 */
			continueReading();
		}
	}
	/*
	 * The reading continuation below reads as much of the request body as is available.
	 * It only ceases to read when ServletInputStream.isReady() returns false.
	 * When that happens, the continuation returns and expects an asynchronous event (handled above) to wake it up again.
	 * Reading also terminates when we reach EOF.
	 * 
	 * In the best (and usual) case, all this code executes synchronously in servlet's handler.
	 * If the request body is empty (as with GET requests), the continuation is guaranteed to complete synchronously.
	 */
	private byte[] bufferIn;
	private static final Counter requestReads = Metrics.counter("hookless.servlet.request.reads");
	private static final Counter requestBytes = Metrics.counter("hookless.servlet.request.bytes");
	private static final Counter requestWaits = Metrics.counter("hookless.servlet.request.waits");
	private synchronized void continueReading() {
		logger.trace("Read callback executed");
		/*
		 * We might get called from some spurious events even after the request body is fully read,
		 * so look for 'executed' flag indicating the reactive factory has been already started and there's nothing to do here.
		 */
		if (!completed && !executed) {
			guard("Failed to read request body").run(Exceptions.sneak().runnable(() -> {
				/*
				 * This is a one-and-a-half loop terminated by isReady() test in the middle.
				 * We want to always check for EOF before we check whether the loop should continue.
				 */
				while (true) {
					logger.trace("Probing input stream.");
					if (streamIn.isFinished()) {
						logger.trace("Input stream is finished.");
						endReading();
						break;
					}
					if (!streamIn.isReady()) {
						logger.trace("Input stream is not ready.");
						requestWaits.increment();
						break;
					}
					logger.trace("Reading input stream.");
					/*
					 * Buffer size could be optimized. Reading 128 bytes at a time might not be most efficient.
					 * We don't want to complicate this code though, because most reactive servlets serve GET requests anyway.
					 * Small value like 128 was chosen, because many requests will likely be short POST requests with form data.
					 */
					if (bufferIn == null)
						bufferIn = new byte[128];
					int count = streamIn.read(bufferIn);
					/*
					 * Ignore EOF signaling via -1 return and rely on isFinished() above to terminate reading.
					 */
					if (count > 0) {
						dataIn.write(bufferIn, 0, count);
						requestReads.increment();
						requestBytes.increment(count);
					}
					logger.trace("Input stream returned {} bytes of data.", count);
				}
			}));
		}
	}
	/*
	 * Reading finishes within guard and synchronization of reading continuation.
	 * For requests without request body, it is guaranteed to always run synchronously in servlet's handler.
	 */
	private void endReading() {
		Exceptions.sneak().run(streamIn::close);
		rrequest.data(dataIn.toByteArray());
		logger.trace("Request contains {} bytes of data.", rrequest.data().length);
		/*
		 * Null all temporary buffers to allow GC to collect them.
		 */
		dataIn = null;
		bufferIn = null;
		execute();
	}
	/*
	 * Now that we have the whole request including request body, we can execute our reactive handler.
	 * This code runs within guard and synchronization of the last reading continuation.
	 * It is however unlikely to trigger any exceptions. It does need the synchronization though.
	 * This method still runs synchronously for requests without request body.
	 */
	private boolean executed;
	private CompletableFuture<ReactiveServletResponse> future;
	private void execute() {
		logger.trace("Starting reactive thread.");
		executed = true;
		/*
		 * Here we jump thread pools. Reactive servlet handler will run on hookless thread pool.
		 * This doesn't feel quite right and it is likely to introduce latency if any one of the two thread pools is busy.
		 * It is nevertheless a simple and concise solution and it is likely required by the nature of AsyncContext.
		 * The only way to run code on servlet container's thread pool is to call AsyncContext.start().
		 * AsyncContext.start() however throws if the AsyncContext happened to be completed meantime for whatever reason,
		 * which is obviously very unhealthy for hookless code controlling execution of the reactive servlet handler.
		 * Perhaps in the future we will figure out some way to remove the thread hopping and associated latency.
		 * 
		 * Reactive servlet allows configuring custom executor, which we propagate to the reactive thread below.
		 * All comments in this class that talk about hookless thread pool actually talk about configured servlet executor.
		 * But then, hookless thread pool is the default servlet executor and it is the most common executor.
		 * To keep things simple, we will assume that hookless thread pool is the servlet executor in all comments.
		 */
		future = OwnerTrace
			.of(ReactiveFuture.supplyReactive(() -> servlet.service(rrequest), servlet.executor()))
			.parent(this)
			.target();
		/*
		 * We have to choose where to run completion callback from the CompletableFuture:
		 * hookless thread pool, container thread pool, or some new special thread pool.
		 * These options can be taken by choosing whenComplete() or one of whenCompleteAsync() methods in CompletableFuture.
		 * Having an extra thread pool seems wasteful and silly, so we want to split the work between hookless and container pools.
		 * We will use whenComplete() on the CompletableFuture to run a short piece of code synchronously on hookless thread pool.
		 * This code will just schedule a more complete handler on servlet container's thread pool.
		 * This way we are keeping most network-related processing on container's thread pool.
		 * 
		 * There is a catch though. Scheduling on container's pool via AsyncContext.start() only works before AsyncContext is completed.
		 * The troubling scenario is that request processing takes too long, timeout kicks in and cancels the reactive thread,
		 * which causes its CompletableFuture to complete exceptionally with CancellationException and
		 * invoke our lightweight hookless-side handler with said exception as a parameter. When this handler runs,
		 * it may find out that timeout processing in servlet container has already completed the AsyncContext.
		 * Subsequent invocation of AsyncContext.start() has undefined behavior since nothing about it is said in the docs.
		 * Jetty, for example, will just throw an exception from AsyncContext.start() when the context is already completed.
		 * 
		 * In order to resolve this problem, we have to check whether we have completed the AsyncContext ourselves
		 * and guard against an exception in case the AsyncContext was completed by servlet container.
		 * In order to check whether we completed the AsyncContext, we need to read the corresponding flag in synchronized block.
		 * This synchronization occurs on hookless thread pool. It's a bit dangerous, because we might happen to compete
		 * for the lock with another thread (running timeout handler for example) that might block on I/O.
		 * Blocking on hookless thread pool is impolite at best. Fortunately, such lock competition is unlikely.
		 * The only two possibilities are timeout and async error on the socket. Both of them have simple, fast handlers.
		 */
		future.whenComplete((rresponse, exception) -> {
			logger.trace("Reactive thread has completed.");
			schedule(rresponse, exception);
		});
	}
	/*
	 * Since the reactive thread is unaware of servlet processing, we have to explicitly cancel it in case of trouble.
	 * This has to be done whenever async error or timeout event is received.
	 * It doesn't have to be called from guard(), because no code runs while we wait for the reactive thread.
	 */
	private void cancel() {
		if (future != null) {
			logger.trace("Cancelling reactive thread.");
			/*
			 * The CompletableFuture was created with ReactiveFuture.supplyReactive(),
			 * which monitors its CompletableFuture and reacts to cancellation by stopping the reactive thread.
			 */
			future.cancel(true);
		}
	}
	/*
	 * Here we are jumping back from hookless thread to servlet container's thread.
	 * This code definitely runs asynchronously. The original servlet handler invocation has probably already ended.
	 * This code still runs on hookless thread, but the invoked serve/fail method will run on container's thread.
	 */
	private synchronized void schedule(ReactiveServletResponse rresponse, Throwable exception) {
		if (!completed) {
			guard("Failed to schedule callback on container's thread pool.").run(() -> {
				async.start(() -> {
					if (exception != null)
						fail(exception);
					else
						serve(rresponse);
				});
			});
		}
	}
	/*
	 * We will first consider the simpler case when the app throws an exception.
	 * Synchronous servlets can throw exceptions and one would expect equivalent functionality in async servlets.
	 * But no, there is no AsyncContext.completeExceptionally(). We have to deal with exceptions ourselves.
	 * 
	 * It is impossible to produce 500 error page that would please everyone.
	 * We will instead return an empty HTTP response carrying nothing but 500 status code.
	 * We are cheating HTTP protocol a little by omitting Content-Type, but it seems to be harmless.
	 * 
	 * Obviously, applications will want something more than blank error page.
	 * They can add response body via servlet filter or via HTTP server in reverse proxy mode.
	 * If the app wants its error responses streamed asynchronously via reactive servlet,
	 * it should handle its own exceptions and convert them to 500 pages of app's choosing.
	 * This is probably the best solution for frameworks built on top of reactive servlet.
	 * 
	 * We still need some way to show the exception to the developer even in default configuration.
	 * We will use logging to accomplish this. Logging an exception is always safe and reasonable.
	 */
	private static final Counter exceptionsService = Metrics.counter("hookless.servlet.exceptions.service");
	private synchronized void fail(Throwable exception) {
		logger.trace("Service exception callback executed.");
		/*
		 * Check for 'responded' flag to avoid double response in case timeout or something else ran first.
		 */
		if (!responded && !completed) {
			/*
			 * We will log the exception loudly as an error,
			 * which should be enough for debugging purposes during development.
			 * It will also cover monitoring in deployment.
			 * If the app doesn't like loud logging, it can filter on logger level or catch its own exceptions.
			 */
			Exceptions.log(logger).handle(exception);
			respond(() -> {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				/*
				 * Never cache HTTP errors caused by application exceptions.
				 * We will add Cache-Control here to ensure the client retries the request.
				 * Again, if the application doesn't like it, it can catch its own exceptions and produce custom responses.
				 */
				response.setHeader("Cache-Control", "no-cache, no-store");
			});
			complete();
			/*
			 * We don't want to count CancellationException cases due to timeout or async errors.
			 * That's why this counter is inside the condition, not outside of it.
			 * The same logic applies to the above exception logger.
			 */
			exceptionsService.increment();
		}
	}
	/*
	 * Finally, we get to the main case of returning a response.
	 * This happens on servlet container's thread pool after we have jumped threads from hookless.
	 */
	private static final Map<Integer, Counter> statusCounters = Collections.synchronizedMap(LazyMap.lazyMap(new HashMap<>(), new Transformer<Integer, Counter>() {
		@Override
		public Counter transform(Integer status) {
			String sanitized = status != null && status >= 100 && status < 600 ? Integer.toString(status) : "other";
			return Metrics.counter("hookless.servlet.status", "status", sanitized);
		}
	}));
	private synchronized void serve(ReactiveServletResponse rresponse) {
		logger.trace("Service completion callback executed.");
		/*
		 * Check for 'responded' flag to avoid double response in case timeout or something else ran first.
		 */
		if (!responded && !completed) {
			respond(Exceptions.sneak().runnable(() -> {
				int status = rresponse.status();
				response.setStatus(status);
				statusCounters.get(status).increment();
				logger.trace("Status code {}.", rresponse.status());
				/*
				 * It is tempting to set some headers (notably Content-Length) automatically,
				 * but that's dangerous practice that will inevitably break some applications.
				 * For example, deriving Content-Length from the size of response's data buffer
				 * will break HEAD requests where it will incorrectly report zero-length content.
				 */
				for (Map.Entry<String, String> header : rresponse.headers().entrySet()) {
					logger.trace("Sending header {}: {}.", header.getKey(), header.getValue());
					response.setHeader(header.getKey(), header.getValue());
				}
				for (Cookie cookie : rresponse.cookies()) {
					logger.trace("Sending cookie {}.", cookie.getName());
					response.addCookie(cookie);
				}
			}));
			beginWriting(rresponse.data());
		}
	}
	/*
	 * Once response header is configured, we can proceed to response streaming.
	 * This is a common case and it is very likely to block. Async write is essential.
	 * We will first perform some setup here and then keep running write continuation until EOF.
	 * This method runs synchronized within serve().
	 */
	private ByteBuffer dataOut;
	private ServletOutputStream streamOut;
	private void beginWriting(ByteBuffer data) {
		if (!completed) {
			guard("Failed to setup response body writing.").run(Exceptions.sneak().runnable(() -> {
				/*
				 * Duplicate the response buffer to avoid modifying its state (mark, position).
				 * 
				 * It is tempting to call rewind() to make sure we read the buffer fully,
				 * but that would prevent applications from returning only a subsection of the buffer.
				 * Portion of the buffer to be used is normally between buffer's position and limit.
				 * If we call rewind(), buffer's position is lost and apps cannot return buffer ranges.
				 */
				dataOut = data.duplicate();
				logger.trace("Preparing to send {} bytes of data.", dataOut.limit());
				streamOut = response.getOutputStream();
				streamOut.setWriteListener(new WriteListener() {
					@Override
					public void onWritePossible() throws IOException {
						/*
						 * This event runs some time after ServletOutputStream.isReady() returned false
						 * in the continuation below. We can just restart the continuation in this case.
						 */
						logger.trace("Async writer signals writability.");
						continueWriting();
					}
					@Override
					public void onError(Throwable ex) {
						logger.trace("Async writer signals error.", ex);
						die(ex);
					}
				});
			}));
			/*
			 * We will run the first continuation synchronously. It is faster this way,
			 * because servlet container might have enough buffer space to accept the whole response body.
			 * But we are also required to do it this way, because servlet container doesn't execute the continuation
			 * unless ServletOutputStream.isReady() returned false before.
			 */
			continueWriting();
		}
	}
	/*
	 * The writing continuation below writes as much of the response body as the servlet container will accept.
	 * It only ceases to write when ServletOutputStream.isReady() returns false.
	 * When that happens, the continuation returns and expects an asynchronous event (handled above) to wake it up again.
	 * Writing also terminates when all data is sent.
	 * 
	 * In the best (and usual) case, all this code executes synchronously in the same servlet container's thread
	 * that wrote response headers and initialized writing above. We jumped to this thread from hookless thread pool.
	 * If the response body is empty, the continuation is guaranteed to complete synchronously.
	 * 
	 * That means, in the best case, there are only two thread jumps per request,
	 * once from container's pool into hookless pool and then from hookless pool back into container's pool.
	 */
	private byte[] bufferOut;
	private static final Counter responseWrites = Metrics.counter("hookless.servlet.response.writes");
	private static final Counter responseBytes = Metrics.counter("hookless.servlet.response.bytes");
	private static final Counter responseWaits = Metrics.counter("hookless.servlet.response.waits");
	private synchronized void continueWriting() {
		logger.trace("Write callback executed.");
		if (!completed) {
			guard("Failed to write response.").run(Exceptions.sneak().runnable(() -> {
				/*
				 * This is a one-and-a-half loop terminated by isReady() test in the middle.
				 * We want to always check for EOF before we check whether the loop should continue.
				 */
				while (true) {
					if (dataOut.remaining() <= 0) {
						logger.trace("All response data has been sent.");
						/*
						 * There is nothing special to do here. Just complete the AsyncContext.
						 */
						complete();
						break;
					}
					if (!streamOut.isReady()) {
						logger.trace("Output stream is not ready.");
						responseWaits.increment();
						break;
					}
					logger.trace("Writing to output stream.");
					/*
					 * We will use 4KB buffer unless the response body is too small.
					 * 4KB fills three packets and it's efficient enough for fast I/O.
					 */
					if (bufferOut == null)
						bufferOut = new byte[Math.min(4096, dataOut.remaining())];
					int written = Math.min(dataOut.remaining(), bufferOut.length);
					/*
					 * Here it gets ridiculous. We are copying contents of one buffer into another.
					 * We do this nonsense, because ServletOutputStream doesn't accept ByteBuffer directly.
					 * We could wrap the stream in a channel, but that throws an exception,
					 * because such wrapper channel is unaware of the isReady() async logic.
					 * Furthermore, the default wrapper channel just uses temporary byte[] buffer anyway.
					 * 
					 * We could optimize by checking whether ByteBuffer has an underlying byte array,
					 * which is the common case, and then read directly from the byte array.
					 * This is however too much complexity at the moment. We can optimize later.
					 * Furthermore, this doesn't address a fairly common case of streaming memory-mapped files.
					 * I believe this can be fully fixed only by having the servlet APIs extended.
					 */
					dataOut.get(bufferOut, 0, written);
					streamOut.write(bufferOut, 0, written);
					responseWrites.increment();
					responseBytes.increment(written);
					logger.trace("Output channel accepted {} bytes of data.", written);
				}
			}));
		}
	}
	@Override
	public String toString() {
		return OwnerTrace.of(this).toString();
	}
}
