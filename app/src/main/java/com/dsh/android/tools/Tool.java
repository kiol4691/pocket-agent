package com.dsh.android.tools;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** A tool the model can call. {@link #schema()} returns the full function object. */
public interface Tool {

    String name();

    JSONObject schema() throws JSONException;

    String execute(String argsJson) throws Exception;

    /** 结构化结果：文本 + 可选图片（read_image 用）。默认没有附件，老工具不用改。 */
    default Result executeRich(String argsJson) throws Exception {
        return new Result(execute(argsJson), null);
    }

    /** 工具执行结果。图片以「规范化后的本地路径」传递，不把 base64 塞进文本。 */
    final class Result {
        public final String text;
        public final List<String> imagePaths;

        public Result(String text, List<String> imagePaths) {
            this.text = text == null ? "" : text;
            this.imagePaths = imagePaths;
        }

        public static Result of(String text) {
            return new Result(text, null);
        }
    }

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