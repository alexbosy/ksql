/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.serde.connect;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.Immutable;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.schema.ksql.PersistenceSchema;
import io.confluent.ksql.schema.ksql.SchemaConverters;
import io.confluent.ksql.schema.ksql.SimpleColumn;
import io.confluent.ksql.schema.ksql.types.SqlType;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.SerdeFeature;
import io.confluent.ksql.serde.SerdeUtils;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.connect.data.ConnectSchema;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Schema.Type;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;

/**
 * Base class for formats that internally leverage Connect's data model, i.e. it's {@link Schema}
 * type.
 *
 * <p>Subclasses need only worry about conversion between the `ParsedSchema` returned from the
 * Schema Registry and Connect's {@link Schema} type. This class handles the conversion between
 * Connect's {@link Schema} type and KSQL's {@link SimpleColumn}.
 */
public abstract class ConnectFormat implements Format {

  private final Function<Schema, Schema> toKsqlTransformer;

  public ConnectFormat() {
    this(new ConnectSchemaTranslator()::toKsqlSchema);
  }

  @VisibleForTesting
  ConnectFormat(final Function<Schema, Schema> toKsqlTransformer) {
    this.toKsqlTransformer = Objects.requireNonNull(toKsqlTransformer, "toKsqlTransformer");
  }

  @Override
  public boolean supportsSchemaInference() {
    return true;
  }

  @Override
  public List<SimpleColumn> toColumns(final ParsedSchema schema) {
    Schema connectSchema = toConnectSchema(schema);

    if (connectSchema.type() != Type.STRUCT) {
      if (!supportsFeature(SerdeFeature.UNWRAP_SINGLES)) {
        throw new KsqlException("Schema returned from schema registry is anonymous type, "
            + "but format " + name() + " does not support anonymous types. "
            + "schema: " + schema);
      }

      connectSchema = SerdeUtils.wrapSingle(connectSchema);
    }

    final Schema rowSchema = toKsqlTransformer.apply(connectSchema);

    return rowSchema.fields().stream()
        .map(ConnectFormat::toColumn)
        .collect(Collectors.toList());
  }

  public ParsedSchema toParsedSchema(
      final PersistenceSchema schema,
      final FormatInfo formatInfo
  ) {
    SerdeUtils.throwOnUnsupportedFeatures(schema.features(), supportedFeatures());

    final ConnectSchema outerSchema = ConnectSchemas.columnsToConnectSchema(schema.columns());
    final ConnectSchema innerSchema = SerdeUtils
        .applySinglesUnwrapping(outerSchema, schema.features());

    return fromConnectSchema(innerSchema, formatInfo);
  }

  @Override
  public Serde<List<?>> getSerde(
      final PersistenceSchema schema,
      final Map<String, String> formatProps,
      final KsqlConfig config,
      final Supplier<SchemaRegistryClient> srFactory
  ) {
    SerdeUtils.throwOnUnsupportedFeatures(schema.features(), supportedFeatures());

    final ConnectSchema outerSchema = ConnectSchemas.columnsToConnectSchema(schema.columns());
    final ConnectSchema innerSchema = SerdeUtils
        .applySinglesUnwrapping(outerSchema, schema.features());

    final Class<?> targetType = SchemaConverters.connectToJavaTypeConverter()
        .toJavaType(innerSchema);

    return schema.features().enabled(SerdeFeature.UNWRAP_SINGLES)
        ? handleUnwrapped(innerSchema, formatProps, config, srFactory, targetType)
        : handleWrapped(innerSchema, formatProps, config, srFactory, targetType);
  }

  private <T> Serde<List<?>> handleUnwrapped(
      final ConnectSchema innerSchema,
      final Map<String, String> formatProps,
      final KsqlConfig config,
      final Supplier<SchemaRegistryClient> srFactory,
      final Class<T> targetType
  ) {
    final Serde<T> innerSerde =
        getConnectSerde(innerSchema, formatProps, config, srFactory, targetType);

    return Serdes.serdeFrom(
        SerdeUtils.unwrappedSerializer(innerSerde.serializer(), targetType),
        SerdeUtils.unwrappedDeserializer(innerSerde.deserializer())
    );
  }

  private Serde<List<?>> handleWrapped(
      final ConnectSchema innerSchema,
      final Map<String, String> formatProps,
      final KsqlConfig config,
      final Supplier<SchemaRegistryClient> srFactory,
      final Class<?> targetType
  ) {
    if (!targetType.equals(Struct.class)) {
      throw new IllegalArgumentException("Expected STRUCT, got " + targetType);
    }

    final Serde<Struct> connectSerde =
        getConnectSerde(innerSchema, formatProps, config, srFactory, Struct.class);

    return Serdes.serdeFrom(
        new ListToStructSerializer(connectSerde.serializer(), innerSchema),
        new StructToListDeserializer(connectSerde.deserializer(), innerSchema.fields().size())
    );
  }

  protected abstract <T> Serde<T> getConnectSerde(
      ConnectSchema connectSchema,
      Map<String, String> formatProps,
      KsqlConfig config,
      Supplier<SchemaRegistryClient> srFactory,
      Class<T> targetType
  );

  protected abstract Schema toConnectSchema(ParsedSchema schema);

  protected abstract ParsedSchema fromConnectSchema(Schema schema, FormatInfo formatInfo);

  private static SimpleColumn toColumn(final Field field) {
    final ColumnName name = ColumnName.of(field.name());
    final SqlType type = SchemaConverters.connectToSqlConverter().toSqlType(field.schema());
    return new ConnectColumn(name, type);
  }

  @Immutable
  private static final class ConnectColumn implements SimpleColumn {

    private final ColumnName name;
    private final SqlType type;

    private ConnectColumn(
        final ColumnName name,
        final SqlType type
    ) {
      this.name = Objects.requireNonNull(name, "name");
      this.type = Objects.requireNonNull(type, "type");
    }

    @Override
    public ColumnName name() {
      return name;
    }

    @Override
    public SqlType type() {
      return type;
    }
  }

  private static class ListToStructSerializer implements Serializer<List<?>> {

    private final Serializer<Struct> inner;
    private final ConnectSchema structSchema;

    ListToStructSerializer(
        final Serializer<Struct> inner,
        final ConnectSchema structSchema
    ) {
      this.inner = Objects.requireNonNull(inner, "inner");
      this.structSchema = Objects.requireNonNull(structSchema, "structSchema");
    }

    @Override
    public void configure(final Map<String, ?> configs, final boolean isKey) {
      inner.configure(configs, isKey);
    }

    @Override
    public byte[] serialize(final String topic, final List<?> values) {
      if (values == null) {
        return null;
      }

      final List<Field> fields = structSchema.fields();

      SerdeUtils.throwOnColumnCountMismatch(fields.size(), values.size(), true, topic);

      final Struct struct = new Struct(structSchema);

      final Iterator<Field> fIt = fields.iterator();
      final Iterator<?> vIt = values.iterator();

      while (fIt.hasNext()) {
        putField(struct, fIt.next(), vIt.next());
      }

      return inner.serialize(topic, struct);
    }

    @Override
    public void close() {
      inner.close();
    }

    private static void putField(final Struct struct, final Field field, final Object value) {
      try {
        struct.put(field, value);
      } catch (DataException e) {
        // Add more info to error message in case of Struct to call out struct schemas
        // with non-optional fields from incorrectly-written UDFs as a potential cause:
        // https://github.com/confluentinc/ksql/issues/5364
        if (!(value instanceof Struct)) {
          throw e;
        } else {
          throw new SerializationException(
              "Failed to prepare Struct value field '" + field.name() + "' for serialization. "
                  + "This could happen if the value was produced by a user-defined function "
                  + "where the schema has non-optional return types. ksqlDB requires all "
                  + "schemas to be optional at all levels of the Struct: the Struct itself, "
                  + "schemas for all fields within the Struct, and so on.",
              e);
        }
      }
    }
  }

  private static class StructToListDeserializer implements Deserializer<List<?>> {

    private final Deserializer<Struct> inner;
    private final int numColumns;

    StructToListDeserializer(final Deserializer<Struct> deserializer, final int numColumns) {
      this.inner = Objects.requireNonNull(deserializer, "deserializer");
      this.numColumns = numColumns;
    }

    @Override
    public void configure(final Map<String, ?> configs, final boolean isKey) {
      inner.configure(configs, isKey);
    }

    @Override
    public List<?> deserialize(final String topic, final byte[] bytes) {
      if (bytes == null) {
        return null;
      }

      final Struct struct = inner.deserialize(topic, bytes);

      final List<Field> fields = struct.schema().fields();

      SerdeUtils.throwOnColumnCountMismatch(numColumns, fields.size(), false, topic);

      final List<Object> values = new ArrayList<>(numColumns);

      for (final Field field : fields) {
        values.add(struct.get(field));
      }

      return values;
    }

    @Override
    public void close() {
      inner.close();
    }
  }
}
