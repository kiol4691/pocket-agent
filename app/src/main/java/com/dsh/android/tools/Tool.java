package com.dsh.android.tools;

import org.json.JSONException;
import org.json.JSONObject;

/** A tool the model can call. {@link #schema()} returns the full function object. */
public interface Tool {

    String name();

    JSONObject schema() throws JSONException;

    String execute(String argsJson) throws Exception;

    static JSONObject function(String name, String description, JSONObject params) throws JSONException {
        JSONObject f = new JSONObject();
        f.put("type", "function");
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", params);
        f.put("function", fn);
        return f;
    }
}