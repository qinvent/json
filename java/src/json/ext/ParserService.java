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

import static json.ext.Parser.ALLOCATOR;

/**
 * The service invoked by JRuby's {@link org.jruby.runtime.load.LoadService LoadService}.
 * Defines the <code>JSON::Ext::Parser</code> class.
 * @author mernen
 */
public class ParserService implements BasicLibraryService {

    public boolean basicLoad(Ruby runtime) {
        runtime.getLoadService().require("json/common");

        RubyModule JSON = runtime.defineModule("JSON");
        RubyClass Parser = JSON.defineModuleUnder("Ext").defineClassUnder("Parser", runtime.getObject(), ALLOCATOR);
        Parser.defineAnnotatedMethods(Parser.class);

        RuntimeInfo.forRuntime(runtime); // initialize

        return true;
    }

}
