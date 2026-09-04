package com.dji.sdk.sample.transport.model;

/** One measured value with explicit provenance and validity. */
public final class TelemetryField {
    private final Object value;
    private final boolean valid;
    private final String source;
    private final int componentIndex;

    public TelemetryField(Object value, boolean valid, String source, int componentIndex) {
        this.value = value;
        this.valid = valid;
        this.source = source;
        this.componentIndex = componentIndex;
    }

    public Object getValue() { return value; }
    public boolean isValid() { return valid; }
    public String getSource() { return source; }
    public int getComponentIndex() { return componentIndex; }
}
