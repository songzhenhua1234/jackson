package org.codehaus.jackson.map.ser;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.*;
import org.codehaus.jackson.map.annotate.JacksonStdImpl;
import org.codehaus.jackson.map.type.TypeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.codehaus.jackson.schema.JsonSchema;
import org.codehaus.jackson.schema.SchemaAware;
import org.codehaus.jackson.type.JavaType;

/**
 * Dummy container class to group standard container serializers: serializers
 * that can serialize things like {@link java.util.List}s,
 * {@link java.util.Map}s and such.
 *<p>
 * TODO: as per [JACKSON-55], should try to add path info for all serializers;
 * is still missing those for some container types.
 */
public final class ContainerSerializers
{
    private ContainerSerializers() { }

    /*
    /**********************************************************
    /* Factory methods
    /**********************************************************
     */
    
    public static ContainerSerializerBase<?> indexedListSerializer(JavaType elemType,
            boolean staticTyping, TypeSerializer vts, BeanProperty property)
    {
        return new IndexedListSerializer(elemType, staticTyping, vts, property);
    }

    public static ContainerSerializerBase<?> collectionSerializer(JavaType elemType,
            boolean staticTyping, TypeSerializer vts, BeanProperty property)
    {
        return new CollectionSerializer(elemType, staticTyping, vts, property);
    }

    public static ContainerSerializerBase<?> iteratorSerializer(JavaType elemType,
            boolean staticTyping, TypeSerializer vts, BeanProperty property)
    {
        return new IteratorSerializer(elemType, staticTyping, vts, property);
    }

    public static ContainerSerializerBase<?> iterableSerializer(JavaType elemType,
            boolean staticTyping, TypeSerializer vts, BeanProperty property)
    {
        return new IterableSerializer(elemType, staticTyping, vts, property);
    }

    public static JsonSerializer<?> enumSetSerializer(JavaType enumType, BeanProperty property)
    {
        return new EnumSetSerializer(enumType, property);
    }
    
    /*
    /**********************************************************
    /* Base classes
    /**********************************************************
     */

    /**
     * Base class for serializers that will output contents as JSON
     * arrays.
     */
    public abstract static class AsArraySerializer<T>
        extends ContainerSerializerBase<T>
        implements ResolvableSerializer
    {
        protected final boolean _staticTyping;

        protected final JavaType _elementType;

        /**
         * Type serializer used for values, if any.
         */
        protected final TypeSerializer _valueTypeSerializer;
        
        /**
         * Value serializer to use, if it can be statically determined
         * 
         * @since 1.5
         */
        protected JsonSerializer<Object> _elementSerializer;

        /**
         * Collection-valued property being serialized with this instance
         * 
         * @since 1.7
         */
        protected final BeanProperty _property;
        
        protected AsArraySerializer(Class<?> cls, JavaType et, boolean staticTyping,
                TypeSerializer vts, BeanProperty property)
        {
            // typing with generics is messy... have to resort to this:
            super(cls, false);
            _elementType = et;
            // static if explicitly requested, or if element type is final
            _staticTyping = staticTyping || (et != null && et.isFinal());
            _valueTypeSerializer = vts;
            _property = property;
        }

        @Override
        public final void serialize(T value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonGenerationException
        {
            jgen.writeStartArray();
            serializeContents(value, jgen, provider);
            jgen.writeEndArray();
        }
        
        @Override
        public final void serializeWithType(T value, JsonGenerator jgen, SerializerProvider provider,
                TypeSerializer typeSer)
            throws IOException, JsonGenerationException
        {
            typeSer.writeTypePrefixForArray(value, jgen);
            serializeContents(value, jgen, provider);
            typeSer.writeTypeSuffixForArray(value, jgen);
        }

        protected abstract void serializeContents(T value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonGenerationException;

        @Override
        public JsonNode getSchema(SerializerProvider provider, Type typeHint)
            throws JsonMappingException
        {
            /* 15-Jan-2010, tatu: This should probably be rewritten, given that
             *    more information about content type is actually being explicitly
             *    passed. So there should be less need to try to re-process that
             *    information.
             */
            ObjectNode o = createSchemaNode("array", true);
            JavaType contentType = null;
            if (typeHint != null) {
                JavaType javaType = TypeFactory.type(typeHint);
                contentType = javaType.getContentType();
                if (contentType == null) { // could still be parametrized (Iterators)
                    if (typeHint instanceof ParameterizedType) {
                        Type[] typeArgs = ((ParameterizedType) typeHint).getActualTypeArguments();
                        if (typeArgs.length == 1) {
                            contentType = TypeFactory.type(typeArgs[0]);
                        }
                    }
                }
            }
            if (contentType == null && _elementType != null) {
                contentType = _elementType;
            }
            if (contentType != null) {
                JsonNode schemaNode = null;
                // 15-Oct-2010, tatu: We can't serialize plain Object.class; but what should it produce here? Untyped?
                if (contentType.getRawClass() != Object.class) {
                    JsonSerializer<Object> ser = provider.findValueSerializer(contentType, _property);
                    if (ser instanceof SchemaAware) {
                        schemaNode = ((SchemaAware) ser).getSchema(provider, null);
                    }
                }
                if (schemaNode == null) {
                    schemaNode = JsonSchema.getDefaultSchemaNode();
                }
                o.put("items", schemaNode);
            }
            return o;
        }

        /**
         * Need to get callback to resolve value serializer, if static typing
         * is used (either being forced, or because value type is final)
         */
        //@Override
        public void resolve(SerializerProvider provider)
            throws JsonMappingException
        {
            if (_staticTyping && _elementType != null) {
                _elementSerializer = provider.findValueSerializer(_elementType, _property);
            }
        }
    }
    
    /*
    /**********************************************************
    /* Concrete serializers, Lists/collections
    /**********************************************************
     */

    /**
     * This is an optimizied serializer for Lists that can be efficiently
     * traversed by index (as opposed to others, such as {@link LinkedList}
     * that can not}.
     */
    @JacksonStdImpl
    public static class IndexedListSerializer
        extends AsArraySerializer<List<?>>
    {
        public IndexedListSerializer(JavaType elemType, boolean staticTyping, TypeSerializer vts,
                BeanProperty property)
        {
            super(List.class, elemType, staticTyping, vts, property);
        }

        @Override
        public ContainerSerializerBase<?> _withValueTypeSerializer(TypeSerializer vts) {
            return new IndexedListSerializer(_elementType, _staticTyping, vts, _property);
        }
        
        @Override
        public void serializeContents(List<?> value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonGenerationException
        {
            if (_elementSerializer != null) {
                serializeContentsUsing(value, jgen, provider, _elementSerializer);
                return;
            }
            if (_valueTypeSerializer != null) {
                serializeTypedContents(value, jgen, provider);
                return;
            }
            final int len = value.size();
            if (len > 0) {
                JsonSerializer<Object> prevSerializer = null;
                Class<?> prevClass = null;
                for (int i = 0; i < len; ++i) {
                    Object elem = value.get(i);
                    try {
                        if (elem == null) {
                            provider.getNullValueSerializer().serialize(null, jgen, provider);
                        } else {
                            // Minor optimization to avoid most lookups:
                            Class<?> cc = elem.getClass();
                            JsonSerializer<Object> currSerializer;
                            if (cc == prevClass) {
                                currSerializer = prevSerializer;
                            } else {
                                currSerializer = provider.findValueSerializer(cc, _property);
                                prevSerializer = currSerializer;
                                prevClass = cc;
                            }
                            currSerializer.serialize(elem, jgen, provider);
                        }
                    } catch (Exception e) {
                        // [JACKSON-55] Need to add reference information
                        wrapAndThrow(e, value, i);
                    }
                }
             }
        }

        public void serializeContentsUsing(List<?> value, JsonGenerator jgen, SerializerProvider provider,
                JsonSerializer<Object> ser)
            throws IOException, JsonGenerationException
        {
            final int len = value.size();
            if (len > 0) {
                final TypeSerializer typeSer = _valueTypeSerializer;
                for (int i = 0; i < len; ++i) {
                    Object elem = value.get(i);
                    try {
                        if (elem == null) {
                            provider.getNullValueSerializer().serialize(null, jgen, provider);
                        } else if (typeSer == null) {
                            ser.serialize(elem, jgen, provider);
                        } else {
                            ser.serializeWithType(elem, jgen, provider, typeSer);
                        }
                    } catch (Exception e) {
                        // [JACKSON-55] Need to add reference information
                        wrapAndThrow(e, value, i);
                    }
                }
             }
        }

        public void serializeTypedContents(List<?> value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonGenerationException
        {
            final int len = value.size();
            if (len > 0) {
                JsonSerializer<Object> prevSerializer = null;
                Class<?> prevClass = null;
                final TypeSerializer typeSer = _valueTypeSerializer;
                for (int i = 0; i < len; ++i) {
                    Object elem = value.get(i);
                    try {
                        if (elem == null) {
                            provider.getNullValueSerializer().serialize(null, jgen, provider);
                        } else {
                            Class<?> cc = elem.getClass();
                            JsonSerializer<Object> currSerializer;
                            if (cc == prevClass) {
                                currSerializer = prevSerializer;
                            } else {
                                currSerializer = provider.findValueSerializer(cc, _property);
                                prevSerializer = currSerializer;
                                prevClass = cc;
                            }
                            currSerializer.serializeWithType(elem, jgen, provider, typeSer);
                        }
                    } catch (Exception e) {
                        // [JACKSON-55] Need to add reference information
                        wrapAndThrow(e, value, i);
                    }
                }
             }
        }
    }

    /**
     * Fallback serializer for cases where Collection is not known to be
     * of type for which more specializer serializer exists (such as
     * index-accessible List).
     * If so, we will just construct an {@link java.util.Iterator}
     * to iterate over elements.
     */
    @JacksonStdImpl
    public static class CollectionSerializer
        extends AsArraySerializer<Collection<?>>
    {
        public CollectionSerializer(JavaType elemType, boolean staticTyping, TypeSerializer vts,
                BeanProperty property)
        {
            super(Collection.class, elemType, staticTyping, vts, property);
        }

        @Override
        public ContainerSerializerBase<?> _withValueTypeSerializer(TypeSerializer vts) {
            return new CollectionSerializer(_elementType, _staticTyping, vts, _property);
        }
        
        @Override
        public void serializeContents(Collection<?> value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonGenerationException
        {
            if (_elementSerializer != null) {
                serializeContentsUsing(value, jgen, provider, _elementSerializer);
                return;
            }
            Iterator<?> it = value.iterator();
            if (it.hasNext()) {
                JsonSerializer<Object> prevSerializer = null;
                Class<?> prevClass = null;
                final TypeSerializer typeSer = _valueTypeSerializer;
    
                int i = 0;
                do {
                    Object elem = it.next();
                    try {
                        if (elem == null) {
                            provider.getNullValueSerializer().serialize(null, jgen, provider);
                        } else {
                            // Minor optimization to avoid most lookups:
                            Class<?> cc = elem.getClass();
                            JsonSerializer<Object> currSerializer;
                            if (cc == prevClass) {
                                currSerializer = prevSerializer;
                            } else {
                                currSerializer = provider.findValueSerializer(cc, _property);
                                prevSerializer = currSerializer;
                                prevClass = cc;
                            }
                            if (typeSer == null) {
                                currSerializer.serialize(elem, jgen, provider);
                            } else {
                                currSerializer.serializeWithType(elem, jgen, provider, typeSer);
                            }
                        }
                    } catch (Exception e) {
                        // [JACKSON-55] Need to add reference information
                        wrapAndThrow(e, value, i);
                    }
                    ++i;
                } while (it.hasNext());
            }
        }

        public void serializeContentsUsing(Collection<?> value, JsonGenerator jgen, SerializerProvider provider,
                JsonSerializer<Object> ser)
            throws IOException, JsonGenerationException
        {
            Iterator<?> it = value.iterator();
            if (it.hasNext()) {
                TypeSerializer typeSer = _valueTypeSerializer;
                int i = 0;
                do {
                    Object elem = it.next();
                    try {
                        if (elem == null) {
                            provider.getNullValueSerializer().serialize(null, jgen, provider);
                        } else {
                            if (typeSer == null) {
                                ser.serialize(elem, jgen, provider);
                            } else {
                                ser.serializeWithType(elem, jgen, provider, typeSer);
                            }
                        }
                        ++i;
                    } catch (Exception e) {
                        // [JACKSON-55] Need to add reference information
                        wrapAndThrow(e, value, i);
                    }
                } while (it.hasNext());
            }
        }
    }

    @JacksonStdImpl
    public static class IteratorSerializer
        extends AsArraySerializer<Iterator<?>>
    {
        public IteratorSerializer(JavaType elemType, boolean staticTyping, TypeSerializer vts,
                BeanProperty property)
        {
            super(Iterator.class, elemType, staticTyping, vts, property);
        }

        @Override
        public ContainerSerializerBase<?> _withValueTypeSerializer(TypeSerializer vts) {
            return new IteratorSerializer(_elementType, _staticTyping, vts, _property);
        }
        
        @Override
        public void serializeContents(Iterator<?> value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonGenerationException
        {
            if (value.hasNext()) {
                final TypeSerializer typeSer = _valueTypeSerializer;
                JsonSerializer<Object> prevSerializer = null;
                Class<?> prevClass = null;
                do {
                    Object elem = value.next();
                    if (elem == null) {
                        provider.getNullValueSerializer().serialize(null, jgen, provider);
                    } else {
                        // Minor optimization to avoid most lookups:
                        Class<?> cc = elem.getClass();
                        JsonSerializer<Object> currSerializer;
                        if (cc == prevClass) {
                            currSerializer = prevSerializer;
                        } else {
                            currSerializer = provider.findValueSerializer(cc, _property);
                            prevSerializer = currSerializer;
                            prevClass = cc;
                        }
                        if (typeSer == null) {
                            currSerializer.serialize(elem, jgen, provider);
                        } else {
                            currSerializer.serializeWithType(elem, jgen, provider, typeSer);
                        }
                    }
                } while (value.hasNext());
            }
        }
    }

    @JacksonStdImpl
    public static class IterableSerializer
        extends AsArraySerializer<Iterable<?>>
    {
        public IterableSerializer(JavaType elemType, boolean staticTyping, TypeSerializer vts, BeanProperty property)
        {
            super(Iterable.class, elemType, staticTyping, vts, property);
        }

        @Override
        public ContainerSerializerBase<?> _withValueTypeSerializer(TypeSerializer vts) {
            return new IterableSerializer(_elementType, _staticTyping, vts, _property);
        }
        
        @Override
        public void serializeContents(Iterable<?> value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonGenerationException
        {
            Iterator<?> it = value.iterator();
            if (it.hasNext()) {
                final TypeSerializer typeSer = _valueTypeSerializer;
                JsonSerializer<Object> prevSerializer = null;
                Class<?> prevClass = null;
                
                do {
                    Object elem = it.next();
                    if (elem == null) {
                        provider.getNullValueSerializer().serialize(null, jgen, provider);
                    } else {
                        // Minor optimization to avoid most lookups:
                        Class<?> cc = elem.getClass();
                        JsonSerializer<Object> currSerializer;
                        if (cc == prevClass) {
                            currSerializer = prevSerializer;
                        } else {
                            currSerializer = provider.findValueSerializer(cc, _property);
                            prevSerializer = currSerializer;
                            prevClass = cc;
                        }
                        if (typeSer == null) {
                            currSerializer.serialize(elem, jgen, provider);
                        } else {
                            currSerializer.serializeWithType(elem, jgen, provider, typeSer);
                        }
                    }
                } while (it.hasNext());
            }
        }
    }

    public static class EnumSetSerializer
        extends AsArraySerializer<EnumSet<? extends Enum<?>>>
    {
        public EnumSetSerializer(JavaType elemType, BeanProperty property)
        {
            super(EnumSet.class, elemType, true, null, property);
        }

        @Override
        public ContainerSerializerBase<?> _withValueTypeSerializer(TypeSerializer vts) {
            // no typing for enums (always "hard" type)
            return this;
        }
        
        @Override
        public void serializeContents(EnumSet<? extends Enum<?>> value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonGenerationException
        {
            JsonSerializer<Object> enumSer = _elementSerializer;
            /* Need to dynamically find instance serializer; unfortunately
             * that seems to be the only way to figure out type (no accessors
             * to the enum class that set knows)
             */
            for (Enum<?> en : value) {
                if (enumSer == null) {
                    /* 12-Jan-2010, tatu: Since enums can not be polymorphic, let's
                     *   not bother with typed serializer variant here
                     */
                    enumSer = provider.findValueSerializer(en.getDeclaringClass(), _property);
                }
                enumSer.serialize(en, jgen, provider);
            }
        }
    }
}
