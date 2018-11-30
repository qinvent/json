/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;

import org.jruby.*;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

/**
 * Library of miscellaneous utility functions
 */
final class Utils {

    static final Encoding ASCII_8BIT = ASCIIEncoding.INSTANCE;
    static final Encoding US_ASCII = USASCIIEncoding.INSTANCE;
    static final Encoding UTF8 = UTF8Encoding.INSTANCE;

    public static final String M_GENERATOR_ERROR = "GeneratorError";
    public static final String M_NESTING_ERROR = "NestingError";
    public static final String M_PARSER_ERROR = "ParserError";

    private Utils() {
        throw new RuntimeException();
    }

    /**
     * Safe {@link RubyArray} type-checking.
     * Returns the given object if it is an <code>Array</code>,
     * or throws an exception if not.
     * @param object The object to test
     * @return The given object if it is an <code>Array</code>
     * @throws RaiseException <code>TypeError</code> if the object is not
     *                        of the expected type
     */
    static RubyArray ensureArray(IRubyObject object) throws RaiseException {
        if (object instanceof RubyArray) return (RubyArray) object;
        return object.convertToArray();
    }

    static RubyHash ensureHash(IRubyObject object) throws RaiseException {
        if (object instanceof RubyHash) return (RubyHash) object;
        return object.convertToHash();
    }

    static RubyString ensureString(IRubyObject object) throws RaiseException {
        if (object instanceof RubyString) return (RubyString)object;
        Ruby runtime = object.getRuntime();
        throw runtime.newTypeError(object, runtime.getString());
    }

    static RaiseException newException(ThreadContext context,
                                       String className, String message) {
        return newException(context, className, context.runtime.newString(message));
    }

    static RaiseException newException(ThreadContext context,
                                       String className, RubyString message) {
        RubyClass klazz = context.runtime.getModule("JSON").getClass(className);
        RubyException ex = (RubyException) klazz.newInstance(context, message, Block.NULL_BLOCK);
        return new RaiseException(ex);
    }

    static byte[] repeat(ByteList a, int n) {
        return repeat(a.unsafeBytes(), a.begin(), a.length(), n);
    }

    static byte[] repeat(byte[] a, int begin, int length, int n) {
        if (length == 0) return ByteList.NULL_ARRAY;
        int resultLen = length * n;
        byte[] result = new byte[resultLen];
        for (int pos = 0; pos < resultLen; pos += length) {
            System.arraycopy(a, begin, result, pos, length);
        }
        return result;
    }

    static RubyString encodeUTF8(ThreadContext context, RubyString str) {
        if (str.getEncoding() != UTF8) {
            RubyEncoding utf8 = context.runtime.getEncodingService().getEncoding(UTF8);
            return (RubyString) str.encode(context, utf8);
        }
        return str;
    }

}
