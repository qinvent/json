/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.runtime.load.BasicLibraryService;

/**
 * The service invoked by JRuby's {@link org.jruby.runtime.load.LoadService LoadService}.
 * Defines the <code>JSON::Ext::Generator</code> module.
 * @author mernen
 */
public class GeneratorService implements BasicLibraryService {

    public boolean basicLoad(Ruby runtime) {
        runtime.getLoadService().require("json/common");

        RubyModule JSON = runtime.defineModule("JSON");
        RubyModule Generator = JSON.defineModuleUnder("Ext").defineModuleUnder("Generator");

        RubyClass State = Generator.defineClassUnder("State", runtime.getObject(), GeneratorState.ALLOCATOR);
        State.defineAnnotatedMethods(GeneratorState.class);

        RubyModule generatorMethods = Generator.defineModuleUnder("GeneratorMethods");
        GeneratorMethods.populate(generatorMethods);

        RuntimeInfo.forRuntime(runtime); // initialize

        return true;
    }

}
