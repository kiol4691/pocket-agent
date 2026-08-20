package com.dsh.android.agent;

/** A requested function call produced by the model. */
public final class ToolCall {

    public final String id;
    public final String name;
    public final String arguments; // JSON string

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    @Override
    public String toString() {
        return name + "(" + arguments + ")";
    }
}