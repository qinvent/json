/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyEncoding;
import org.jruby.RubyModule;
import org.jruby.runtime.builtin.IRubyObject;

import static json.ext.Utils.UTF8;

final class RuntimeInfo {

    static RuntimeInfo initRuntime(Ruby runtime, final RubyModule JSON) {
        synchronized (runtime) {
            RuntimeInfo info = new RuntimeInfo(runtime);
            JSON.dataWrapStruct(info);
            return info;
        }
    }

    public static RuntimeInfo forRuntime(Ruby runtime) {
        RubyModule JSON = runtime.getModule("JSON");
        Object info = JSON.dataGetStruct();
        if (info == null) {
            synchronized (runtime) {
                info = JSON.dataGetStruct();
                if (info == null) info = initRuntime(runtime, JSON);
            }
        }
        return (RuntimeInfo) info;
    }

    final RubyModule JSON; // JSON

    private transient RubyClass GeneratorState; // JSON::Ext::Generator::State
    private transient RubyModule StringExtend; // JSON::Ext::Generator::GeneratorMethods::String::Extend

    private RuntimeInfo(Ruby runtime) {
        JSON = runtime.getModule("JSON");
    }

    public RubyClass getGeneratorState() {
        final RubyClass mod = GeneratorState;
        if (mod == null) {
            return GeneratorState = getGenerator().getClass("State");
        }
        return mod;
    }

    private RubyModule getGenerator() {
        return (RubyModule) ((RubyModule) JSON.getConstantAt("Ext")).getConstantAt("Generator");
    }

    RubyModule getStringExtend() {
        final RubyModule mod = StringExtend;
        if (mod == null) {
            return StringExtend = (RubyModule) ((RubyModule)
                    ((RubyModule) getGenerator().getConstantAt("GeneratorMethods"))
                            .getConstantAt("String")).getConstantAt("Extend");
        }
        return mod;
    }

    private transient GeneratorState SAFE_STATE_PROTOTYPE; // JSON::SAFE_STATE_PROTOTYPE

    GeneratorState getSafeStatePrototype() {
        GeneratorState prototype = SAFE_STATE_PROTOTYPE;
        if (prototype == null) {
            IRubyObject value = JSON.getConstant("SAFE_STATE_PROTOTYPE");
            if (!(value instanceof GeneratorState)) {
                final Ruby runtime = JSON.getRuntime();
                throw runtime.newTypeError(value, getGeneratorState());
            }
            return SAFE_STATE_PROTOTYPE = (GeneratorState) value;
        }
        return prototype;
    }

    transient RubyEncoding encodingUTF8;

    RubyEncoding getEncodingUTF8() {
        if (encodingUTF8 == null) {
            final Ruby runtime = JSON.getRuntime();
            return encodingUTF8 = runtime.getEncodingService().getEncoding(UTF8);
        }
        return encodingUTF8;
    }

}
