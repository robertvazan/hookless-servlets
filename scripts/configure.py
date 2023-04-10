# This script generates and updates project configuration files.

# Run this script with rvscaffold in PYTHONPATH
import rvscaffold as scaffold

class Project(scaffold.Java):
    def script_path_text(self): return __file__
    def repository_name(self): return 'hookless-servlets'
    def pretty_name(self): return 'Hookless Servlets'
    def pom_description(self): return 'Reactive adapters for jakarta.servlet.* classes.'
    def inception_year(self): return 2018
    def jdk_version(self): return 17
    def stagean_annotations(self): return True
    
    def dependencies(self):
        yield from super().dependencies()
        yield self.use_hookless()
        # We are not using official Jakarta servlet API dependency, because it is reportedly broken.
        # https://github.com/eclipse/jetty.project/issues/6947
        yield self.use('org.eclipse.jetty.toolchain:jetty-jakarta-servlet-api:5.0.2')
        yield self.use_commons_collections()
        yield self.use_junit()
        yield self.use_hamcrest()
        yield self.use_mockito()
        yield self.use_slf4j_test()

Project().generate()
