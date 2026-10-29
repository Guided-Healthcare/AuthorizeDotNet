#!/usr/bin/env python3
"""Run isolated real-service tests using an existing Moqui build/cache; installs nothing."""
import os
from pathlib import Path
import subprocess
import sys

framework = Path(os.environ['MOQUI_FRAMEWORK_ROOT'])
harness = Path(os.environ['XML_SERVICE_HARNESS'])
assert (framework / 'template/XmlActions.groovy.ftl').is_file()
assert harness.is_file()
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
modules = ['org.apache.groovy/groovy/5.0.3', 'org.apache.groovy/groovy-xml/5.0.3',
           'org.freemarker/freemarker/2.3.34', 'org.slf4j/slf4j-api/2.0.17',
           'jakarta.transaction/jakarta.transaction-api/2.0.1', 'jakarta.servlet/jakarta.servlet-api/6.1.0',
           'commons-codec/commons-codec/1.20.0', 'commons-io/commons-io/2.21.0',
           'org.apache.commons/commons-fileupload2-core/2.0.0-M4',
           'org.apache.commons/commons-fileupload2-jakarta-servlet6/2.0.0-M4']
jars = [str(p) for module in modules for p in (cache / module).rglob('*.jar')]
jars += [str(p) for group in cache.glob('org.eclipse.jetty*') for p in group.rglob('*.jar') if '/12.1.5/' in str(p)]
jars += [str(p) for p in (framework / 'build/libs').glob('moqui-framework-*.jar')]
assert any('/groovy-5.0.3.jar' in p for p in jars) and any('/moqui-framework-' in p for p in jars)
os.chdir(Path(__file__).resolve().parent.parent)
sys.exit(subprocess.run(['java', '-cp', os.pathsep.join(jars), 'groovy.ui.GroovyMain',
                         'tests/HostedProfileOwnerTest.groovy'], env=os.environ.copy()).returncode)
