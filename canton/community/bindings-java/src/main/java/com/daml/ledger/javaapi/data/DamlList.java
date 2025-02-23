// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates.
// Proprietary code. All rights reserved.

package com.daml.ledger.javaapi.data;

import com.daml.ledger.api.v1.ValueOuterClass;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.NonNull;

public final class DamlList extends Value {

  private List<Value> values;

  private DamlList() {}

  /** The list that is passed to this constructor must not be change once passed. */
  static @NonNull DamlList fromPrivateList(@NonNull List<@NonNull Value> values) {
    DamlList damlList = new DamlList();
    damlList.values = Collections.unmodifiableList(values);
    return damlList;
  }

  public static DamlList of(@NonNull List<@NonNull Value> values) {
    return fromPrivateList(new ArrayList<>(values));
  }

  public static DamlList of(@NonNull Value... values) {
    return fromPrivateList(Arrays.asList(values));
  }

  public @NonNull Stream<Value> stream() {
    return values.stream();
  }

  public @NonNull <T> List<T> toList(Function<Value, T> valueMapper) {
    return stream().map(valueMapper).collect(Collectors.toList());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DamlList list = (DamlList) o;
    return Objects.equals(values, list.values);
  }

  @Override
  public int hashCode() {
    return Objects.hash(values);
  }

  @Override
  public String toString() {
    return "DamlList{" + "values=" + values + '}';
  }

  @Override
  public ValueOuterClass.Value toProto() {
    ValueOuterClass.List.Builder builder = ValueOuterClass.List.newBuilder();
    for (Value value : this.values) {
      builder.addElements(value.toProto());
    }
    return ValueOuterClass.Value.newBuilder().setList(builder.build()).build();
  }

  public static @NonNull DamlList fromProto(ValueOuterClass.List list) {
    return list.getElementsList().stream().collect(DamlCollectors.toDamlList(Value::fromProto));
  }
}
