package com.dsh.android.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** In-memory todo list for multi-step tasks. */
public final class TodoTool implements Tool {

    private static final class Item {
        final int id;
        final String task;
        String status = "pending";

        Item(int id, String task) {
            this.id = id;
            this.task = task;
        }
    }

    private static int nextId = 1;
    private static final List<Item> items = new ArrayList<>();

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("action", new JSONObject()
                .put("type", "string")
                .put("description", "add | list | done")
                .put("enum", new JSONArray().put("add").put("list").put("done")));
        props.put("task", new JSONObject()
                .put("type", "string")
                .put("description", "Task text for action=add."));
        props.put("id", new JSONObject()
                .put("type", "number")
                .put("description", "Item id for action=done."));
        params.put("properties", props);
        params.put("required", new JSONArray().put("action"));
        return Tool.function("todo", "Maintain a short todo list for multi-step tasks.", params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject o = new JSONObject(argsJson);
        String action = o.optString("action", "");
        synchronized (items) {
            switch (action) {
                case "add": {
                    String task = o.optString("task", "").trim();
                    if (task.isEmpty()) return "Error: task text required";
                    Item it = new Item(nextId++, task);
                    items.add(it);
                    return "Added todo #" + it.id + ": " + task + " (" + items.size() + " open)";
                }
                case "list": {
                    if (items.isEmpty()) return "(no todos)";
                    StringBuilder sb = new StringBuilder();
                    for (Item it : items) sb.append("#").append(it.id)
                            .append(" [").append(it.status).append("] ").append(it.task).append("\n");
                    return sb.toString().trim();
                }
                case "done": {
                    int id = o.optInt("id", -1);
                    for (Item it : items) {
                        if (it.id == id) {
                            it.status = "done";
                            return "Todo #" + id + " marked done";
                        }
                    }
                    return "Error: no todo with id " + id;
                }
                default:
                    return "Error: unknown action '" + action + "'. Valid: add, list, done";
            }
        }
    }
}