import freemarker.template.Configuration
import org.moqui.util.MNode
import org.moqui.util.ContextStack
import org.moqui.util.ContextBinding
import org.moqui.entity.EntityValue
import java.lang.reflect.Proxy
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

/** Compiles production XML actions using the real Moqui template. */
class XmlServiceHarness {
    static Script compile(String file, String verb, String noun, Map context) {
        def service = MNode.parse(new File(file)).children('service').find {
            it.attribute('verb') == verb && it.attribute('noun') == noun
        }
        assert service: "Missing production service ${verb}#${noun}"
        compileActions(service.first('actions'), context)
    }

    static Script compileActions(MNode actions, Map context) {
        def cfg = new Configuration(Configuration.VERSION_2_3_34)
        cfg.setDirectoryForTemplateLoading(new File(System.getenv('MOQUI_FRAMEWORK_ROOT'), 'template'))
        cfg.setDefaultEncoding('UTF-8')
        def writer = new StringWriter()
        cfg.getTemplate('XmlActions.groovy.ftl').process([xmlActionsRoot:actions], writer)
        def variables = new LinkedHashMap(context)
        // Production scripts use the context map and ordinary binding names.
        def stack = new ContextStack()
        stack.push(variables)
        def compiler = new CompilerConfiguration()
        compiler.addCompilationCustomizers(new ImportCustomizer().addImports('org.moqui.entity.EntityValue'))
        def script = new GroovyShell(this.classLoader, compiler).parse(writer.toString())
        script.binding = new ContextBinding(stack)
        script
    }

    /** Map-backed test value. An optional callback models/observes the persistence boundary;
     * callback failures must reach the production action rather than look like successful writes. */
    static EntityValue value(Map row, Closure onUpdate = null) {
        if (row == null) return null
        Proxy.newProxyInstance(EntityValue.classLoader, [EntityValue] as Class[], { proxy, method, args ->
            if (method.name == 'toString') return row.toString()
            if (method.name == 'getEntityName') return 'IsolatedFixture'
            if (method.name == 'getMap') return row
            if (method.name == 'getString') return row[args[0]]?.toString()
            if (method.name == 'get') return row[args[0]]
            if (method.name == 'set') { row.put(args[0], args[1]); return proxy }
            if (method.name == 'put') return row.put(args[0], args[1])
            if (method.name == 'update') { onUpdate?.call(row); return proxy }
            if (method.name == 'equals') return proxy.is(args[0])
            if (method.name == 'hashCode') return System.identityHashCode(proxy)
            def target = row.getClass().getMethod(method.name, method.parameterTypes)
            target.invoke(row, args)
        } as java.lang.reflect.InvocationHandler) as EntityValue
    }
}
