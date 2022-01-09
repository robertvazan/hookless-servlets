# This script generates and updates project configuration files.

# We are assuming that project-config is available in sibling directory.
# Checkout from https://github.com/robertvazan/project-config
import os.path
import sys
sys.path.append(os.path.normpath(os.path.join(__file__, '../../../project-config/src')))

from java import *

project_script_path = __file__
repository_name = lambda: 'hookless-servlets'
pretty_name = lambda: 'Hookless Servlets'
pom_subgroup = lambda: 'hookless'
pom_description = lambda: 'Reactive adapters for jakarta.servlet.* classes.'
inception_year = lambda: 2018
jdk_version = lambda: 11
stagean_annotations = lambda: True
homepage = lambda: website() + 'servlets'
javadoc_site = lambda: website() + 'javadocs/servlets/'
project_status = lambda: experimental_status()

def dependencies():
    use_hookless()
    # We are not using official Jakarta servlet API dependency, because it is reportedly broken.
    # https://github.com/eclipse/jetty.project/issues/6947
    use('org.eclipse.jetty.toolchain:jetty-jakarta-servlet-api:5.0.2')
    use_commons_collections()
    use_junit()
    use_hamcrest()
    use_mockito()
    use_slf4j_test()

generate(globals())
