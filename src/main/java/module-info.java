// Part of Hookless Servlets: https://hookless.machinezoo.com/servlets
import com.machinezoo.stagean.*;

/**
 * Reactive versions of classes from {@link jakarta.servlet.http}.
 * See {@link com.machinezoo.hookless.servlets} package.
 */
@ApiIssue("maybe jetty jakarta libs")
module com.machinezoo.hookless.servlets {
	exports com.machinezoo.hookless.servlets;
	requires com.machinezoo.stagean;
	requires com.machinezoo.hookless;
	/*
	 * Transitive, because ReactiveServlet inherits from HttpServlet.
	 * We are not using official Jakarta servlet API dependency, because it is reportedly broken.
	 * https://github.com/eclipse/jetty.project/issues/6947
	 * This will have to be fixed when Jakarta EE 10 and Jakarta servlet API 6 are out.
	 * Meantime we are limited to deploying to Jetty server.
	 */
	requires transitive jetty.servlet.api;
	requires org.apache.commons.collections4;
	/*
	 * SLF4J is pulled in transitively via noexception and then via hookless,
	 * but the transitive dependency will be removed in future versions of noexception.
	 */
	requires org.slf4j;
	requires micrometer.core;
}
