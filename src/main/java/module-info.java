// Part of Hookless Servlets: https://hookless.machinezoo.com/servlets
/**
 * Reactive versions of classes from {@link jakarta.servlet.http}.
 * See {@link com.machinezoo.hookless.servlets} package.
 */
module com.machinezoo.hookless.servlets {
	exports com.machinezoo.hookless.servlets;
	requires com.machinezoo.stagean;
	requires com.machinezoo.hookless;
	requires transitive jakarta.servlet;
	requires org.apache.commons.collections4;
}
