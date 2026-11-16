package com.dsh.android.plugin;

import com.dsh.android.tools.Tool;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 把 manifest 里的一条工具声明包装成宿主 {@link Tool}。
 *
 * 这一层刻意很薄：只提供模型侧的名字与 schema，执行、参数校验、超时和错误处理都在
 * {@link HookRegistry} / {@link HookEngine}，避免两条调用路径出现行为差异。
 */
public final class PluginTool implements Tool {

    private final HookRegistry registry;
    private final HookManifest manifest;
    private final ToolDecl decl;

    PluginTool(HookRegistry registry, HookManifest manifest, ToolDecl decl) {
        this.registry = registry;
        this.manifest = manifest;
        this.decl = decl;
    }

    @Override
    public String name() {
        return decl.name;
    }

    public String pluginId() {
        return manifest.id;
    }

    /** 工具声明里的 priority（重名冲突与列表排序用）。 */
    public int priority() {
        return decl.priority;
    }

    @Override
    public JSONObject schema() throws JSONException {
        return Tool.function(decl.name, decl.description, decl.inputSchema);
    }

    @Override
    public String execute(String argsJson) {
        return registry.invokeTool(manifest, decl, argsJson);
    }

    @Override
    public String toString() {
        return "PluginTool(" + manifest.id + "/" + decl.name + ")";
    }
}
