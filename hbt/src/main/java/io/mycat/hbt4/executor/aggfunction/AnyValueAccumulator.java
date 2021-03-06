package io.mycat.hbt4.executor.aggfunction;

import io.mycat.mpp.Row;
import org.apache.calcite.rel.core.AggregateCall;

import java.util.Objects;

/**
 * Accumulator for calls to the COUNT function.
 */
public class AnyValueAccumulator implements Accumulator {
    private final AggregateCall call;
    Object value;

    AnyValueAccumulator(AggregateCall call) {
        this.call = call;
        this.value = null;
    }

    public void send(Row row) {
        if (value == null) {
            Integer integer = call.getArgList().get(0);
            this.value = row.getObject(integer);
        }
    }

    public Object end() {
        return this.value;
    }
}
