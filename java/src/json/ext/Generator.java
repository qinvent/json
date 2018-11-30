/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBasicObject;
import org.jruby.RubyBignum;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyFixnum;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyNil;
import org.jruby.RubyString;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.ConvertBytes;

import static json.ext.Utils.*;

public final class Generator {
    private Generator() {
        throw new RuntimeException();
    }

    /**
     * Encodes the given object as a JSON string, using the given handler.
     */
    static <T extends IRubyObject> RubyString
            generateJson(ThreadContext context, T object,
                         Handler<? super T> handler, IRubyObject[] args) {
        Session session = new Session(context, args.length > 0 ? args[0] : null);
        return session.infect(handler.generateNew(session, object));
    }

    /**
     * Encodes the given object as a JSON string, detecting the appropriate handler
     * for the given object.
     */
    static <T extends IRubyObject> RubyString
            generateJson(ThreadContext context, T object, IRubyObject[] args) {
        return generateJson(context, object, getHandlerFor(context.runtime, object), args);
    }

    /**
     * Encodes the given object as a JSON string, using the appropriate
     * handler if one is found or calling #to_json if not.
     */
    public static <T extends IRubyObject> RubyString
            generateJson(ThreadContext context, T object, GeneratorState config) {
        return getHandlerFor(context.runtime, object).generateNew(new Session(context, config), object);
    }

    // NOTE: drop this once Ruby 1.9.3 support is gone!
    private static final int FIXNUM = 1;
    private static final int BIGNUM = 2;
    private static final int ARRAY = 3;
    private static final int STRING = 4;
    private static final int NIL = 5;
    private static final int TRUE = 6;
    private static final int FALSE = 7;
    private static final int HASH = 10;
    private static final int FLOAT = 11;
    // hard-coded due JRuby 1.7 compatibility
    // https://github.com/jruby/jruby/blob/1.7.27/core/src/main/java/org/jruby/runtime/ClassIndex.java

    /**
     * Returns the best serialization handler for the given object.
     */
    // Java's generics can't handle this satisfactorily, so I'll just leave
    // the best I could get and ignore the warnings
    @SuppressWarnings("unchecked")
    private static <T extends IRubyObject>
            Handler<? super T> getHandlerFor(Ruby runtime, T object) {
        switch (((RubyBasicObject) object).getNativeTypeIndex()) {
            // can not use getNativeClassIndex due 1.7 compatibility
            case NIL    : return (Handler) NIL_HANDLER;
            case TRUE   : return (Handler) TRUE_HANDLER;
            case FALSE  : return (Handler) FALSE_HANDLER;
            case FLOAT  : return (Handler) FLOAT_HANDLER;
            case FIXNUM : return (Handler) FIXNUM_HANDLER;
            case BIGNUM : return (Handler) BIGNUM_HANDLER;
            case STRING :
                if (((RubyBasicObject) object).getMetaClass() != runtime.getString()) break;
                return (Handler) STRING_HANDLER;
            case ARRAY  :
                if (((RubyBasicObject) object).getMetaClass() != runtime.getArray()) break;
                return (Handler) ARRAY_HANDLER;
            case HASH   :
                if (((RubyBasicObject) object).getMetaClass() != runtime.getHash()) break;
                return (Handler) HASH_HANDLER;
        }
        return GENERIC_HANDLER;
    }


    /* Generator context */

    /**
     * A class that concentrates all the information that is shared by
     * generators working on a single session.
     *
     * <p>A session is defined as the process of serializing a single root
     * object; any handler directly called by container handlers (arrays and
     * hashes/objects) shares this object with its caller.
     *
     * <p>Note that anything called indirectly (via {@link GENERIC_HANDLER})
     * won't be part of the session.
     */
    static final class Session {
        private final ThreadContext context;
        private GeneratorState state;
        private IRubyObject possibleState;
        private RuntimeInfo info;
        private StringEncoder stringEncoder;

        private boolean tainted = false;
        private boolean untrusted = false;

        Session(ThreadContext context, GeneratorState state) {
            this.context = context;
            this.state = state;
        }

        Session(ThreadContext context, IRubyObject possibleState) {
            this.context = context;
            this.possibleState = possibleState == null || possibleState.isNil() ? null : possibleState;
        }

        public GeneratorState getState() {
            if (state == null) {
                RubyClass klass = getInfo().getGeneratorState();
                state = GeneratorState.fromState(context, getInfo(), klass, possibleState);
            }
            return state;
        }

        public RuntimeInfo getInfo() {
            if (info == null) info = RuntimeInfo.forRuntime(context.runtime);
            return info;
        }

        public StringEncoder getStringEncoder() {
            if (stringEncoder == null) {
                stringEncoder = new StringEncoder(context.runtime, getState().asciiOnly());
            }
            return stringEncoder;
        }

        public void infectBy(IRubyObject object) {
            if (object.isTaint()) tainted = true;
            if (object.isUntrusted()) untrusted = true;
        }

        public <T extends IRubyObject> T infect(T object) {
            if (tainted) object.setTaint(true);
            if (untrusted) object.setUntrusted(true);
            return object;
        }
    }


    /* Handler base classes */

    private static abstract class Handler<T extends IRubyObject> {

        static final ByteList EMPTY = ByteList.EMPTY_BYTELIST;

        /**
         * Returns an estimative of how much space the serialization of the
         * given object will take. Used for allocating enough buffer space
         * before invoking other methods.
         */
        int guessSize(Session session, T object) {
            return 4;
        }

        RubyString generateNew(Session session, T object) {
            ByteList buffer = new ByteList(guessSize(session, object));
            generate(session, object, buffer);
            ThreadContext context = session.context;
            RubyString str = RubyString.newString(context.runtime, buffer);
            str.associateEncoding(UTF8);
            return str;
        }

        abstract void generate(Session session, T object, ByteList buffer);
    }

    /**
     * A handler that returns a fixed keyword regardless of the passed object.
     */
    private static class KeywordHandler<T extends IRubyObject> extends Handler<T> {

        private final ByteList keyword;

        private KeywordHandler(String keyword) {
            this.keyword = new ByteList(ByteList.plain(keyword), UTF8, false);
        }

        @Override
        int guessSize(Session session, T object) {
            return keyword.length();
        }

        @Override
        RubyString generateNew(Session session, T object) {
            return RubyString.newStringShared(session.context.runtime, keyword);
        }

        @Override
        void generate(Session session, T object, ByteList buffer) {
            buffer.append(keyword);
        }
    }


    /* Handlers */

    static final Handler<RubyBignum> BIGNUM_HANDLER =
        new Handler<RubyBignum>() {
            @Override
            void generate(Session session, RubyBignum object, ByteList buffer) {
                buffer.append(((RubyString) object.to_s()).getByteList()); // US-ASCII
            }
        };

    static final Handler<RubyFixnum> FIXNUM_HANDLER =
        new Handler<RubyFixnum>() {
            @Override
            void generate(Session session, RubyFixnum object, ByteList buffer) {
                buffer.append(ConvertBytes.longToByteList(object.getLongValue(), 10));
            }
        };

    static final Handler<RubyFloat> FLOAT_HANDLER =
        new Handler<RubyFloat>() {
            @Override
            void generate(Session session, RubyFloat object, ByteList buffer) {
                double value = RubyFloat.num2dbl(object);

                if (Double.isInfinite(value) || Double.isNaN(value)) {
                    if (!session.getState().allowNaN()) {
                        throw newException(session.context, M_GENERATOR_ERROR, object + " not allowed in JSON");
                    }
                }
                buffer.append(((RubyString) object.to_s()).getByteList()); // US-ASCII
            }
        };

    static final Handler<RubyArray> ARRAY_HANDLER =
        new Handler<RubyArray>() {
            @Override
            int guessSize(Session session, RubyArray object) {
                GeneratorState state = session.getState();
                int depth = state.getDepth();
                int perItem =
                    4                                           // prealloc
                    + (depth + 1) * state.getIndent().length()  // indent
                    + 1 + state.getArrayNl().length();          // ',' arrayNl
                return 2 + object.size() * perItem;
            }

            @Override
            void generate(Session session, RubyArray object, ByteList buffer) {
                final ThreadContext context = session.context;
                final Ruby runtime = context.runtime;

                GeneratorState state = session.getState();
                int depth = state.increaseDepth();

                ByteList indentUnit = state.getIndent();
                byte[] shift = repeat(indentUnit, depth);

                session.infectBy(object);

                buffer.append((byte)'[');

                ByteList arrayNl = state.getArrayNl();
                if (arrayNl == EMPTY) {
                    appendSimple(session, runtime, object, shift, buffer);
                } else {
                    buffer.append(arrayNl);

                    byte[] delim = new byte[1 + arrayNl.length()];
                    delim[0] = ',';
                    System.arraycopy(arrayNl.unsafeBytes(), arrayNl.begin(), delim, 1, arrayNl.length());

                    appendDelim(session, runtime, object, delim, shift, buffer);
                }

                state.decreaseDepth();
                if (arrayNl.length() != 0) {
                    buffer.append(arrayNl);
                    buffer.append(shift, 0, state.getDepth() * indentUnit.length());
                }

                buffer.append((byte)']');
            }

            private void appendSimple(final Session session, final Ruby runtime, RubyArray object,
                                      final byte[] shift, final ByteList buffer) {
                boolean firstItem = true;
                for (int i = 0; i < object.getLength(); i++) {
                    IRubyObject element = object.eltInternal(i);
                    session.infectBy(element);
                    if (firstItem) {
                        firstItem = false;
                    } else {
                        buffer.append(',');
                    }
                    if (shift.length > 0) buffer.append(shift);
                    getHandlerFor(runtime, element).generate(session, element, buffer);
                }
            }

            private void appendDelim(final Session session, final Ruby runtime, RubyArray object,
                                     final byte[] delim, final byte[] shift, final ByteList buffer) {
                boolean firstItem = true;
                for (int i = 0; i < object.getLength(); i++) {
                    IRubyObject element = object.eltInternal(i);
                    session.infectBy(element);
                    if (firstItem) {
                        firstItem = false;
                    } else {
                        buffer.append(delim);
                    }
                    if (shift.length > 0) buffer.append(shift);
                    getHandlerFor(runtime, element).generate(session, element, buffer);
                }
            }
        };

    static final Handler<RubyHash> HASH_HANDLER =
        new Handler<RubyHash>() {
            @Override
            int guessSize(Session session, RubyHash object) {
                GeneratorState state = session.getState();
                int perItem =
                    12    // key, colon, comma
                    + (state.getDepth() + 1) * state.getIndent().length()
                    + state.getSpaceBefore().length()
                    + state.getSpace().length();
                return 2 + object.size() * perItem;
            }

            @Override
            void generate(final Session session, RubyHash object, final ByteList buffer) {
                ThreadContext context = session.context;
                final Ruby runtime = context.runtime;
                final GeneratorState state = session.getState();
                final int depth = state.increaseDepth();

                final ByteList objectNl = state.getObjectNl();
                final byte[] indent = repeat(state.getIndent(), depth);
                final ByteList spaceBefore = state.getSpaceBefore();
                final ByteList space = state.getSpace();

                buffer.append((byte)'{');
                buffer.append(objectNl);
                object.visitAll(new RubyHash.Visitor() {
                    private boolean firstPair = true;

                    @Override
                    public void visit(IRubyObject key, IRubyObject value) {
                        if (firstPair) {
                            firstPair = false;
                        } else {
                            buffer.append((byte)',');
                            buffer.append(objectNl);
                        }
                        if (objectNl.length() != 0) buffer.append(indent);

                        STRING_HANDLER.generate(session, key.asString(), buffer);
                        session.infectBy(key);

                        buffer.append(spaceBefore);
                        buffer.append((byte)':');
                        buffer.append(space);

                        getHandlerFor(runtime, value).generate(session, value, buffer);
                        session.infectBy(value);
                    }
                });
                state.decreaseDepth();
                if (objectNl.length() != 0) {
                    buffer.append(objectNl);
                    buffer.append(repeat(state.getIndent(), state.getDepth()));
                }
                buffer.append((byte)'}');
            }
        };

    static final Handler<RubyString> STRING_HANDLER =
        new Handler<RubyString>() {
            @Override
            int guessSize(Session session, RubyString object) {
                // for most applications, most strings will be just a set of
                // printable ASCII characters without any escaping, so let's
                // just allocate enough space for that + the quotes
                return 2 + object.getByteList().length();
            }

            @Override
            void generate(Session session, RubyString object, ByteList buffer) {
                RuntimeInfo info = session.getInfo();
                RubyString src;

                if (object.getEncoding() != UTF8) {
                    src = (RubyString) object.encode(session.context, info.getEncodingUTF8());
                } else {
                    src = object;
                }

                session.getStringEncoder().encode(src.getByteList(), buffer);
            }
        };

    static final Handler<RubyBoolean> TRUE_HANDLER = new KeywordHandler<RubyBoolean>("true");
    static final Handler<RubyBoolean> FALSE_HANDLER = new KeywordHandler<RubyBoolean>("false");
    static final Handler<RubyNil> NIL_HANDLER = new KeywordHandler<RubyNil>("null");

    /**
     * The default handler (<code>Object#to_json</code>): coerces the object
     * to string using <code>#to_s</code>, and serializes that string.
     */
    static final Handler<IRubyObject> OBJECT_HANDLER =
        new Handler<IRubyObject>() {
            @Override
            RubyString generateNew(Session session, IRubyObject object) {
                RubyString str = object.asString();
                return STRING_HANDLER.generateNew(session, str);
            }

            @Override
            void generate(Session session, IRubyObject object, ByteList buffer) {
                RubyString str = object.asString();
                STRING_HANDLER.generate(session, str, buffer);
            }
        };

    /**
     * A handler that simply calls <code>#to_json(state)</code> on the
     * given object.
     */
    static final Handler<IRubyObject> GENERIC_HANDLER =
        new Handler<IRubyObject>() {
            @Override
            RubyString generateNew(Session session, IRubyObject object) {
                ThreadContext context = session.context;
                if (object.respondsTo("to_json")) {
                    IRubyObject result = object.callMethod(context, "to_json", session.getState());
                    if (result instanceof RubyString) return (RubyString) result;
                    throw context.runtime.newTypeError("to_json must return a String");
                }
                return OBJECT_HANDLER.generateNew(session, object);
            }

            @Override
            void generate(Session session, IRubyObject object, ByteList buffer) {
                RubyString result = generateNew(session, object);
                buffer.append(result.getByteList());
            }
        };
}
