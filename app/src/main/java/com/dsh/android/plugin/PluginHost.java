package com.dsh.android.plugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * bridge.* 需要的宿主服务。PluginBridge 只依赖这个接口，Android 相关实现放在
 * {@code AndroidPluginHost}，单测/无界面场景可以直接用默认实现（全部返回不可用）。
 *
 * 所有方法都可能从 QuickJS 线程调用；实现方负责切主线程，不能假设调用线程。
 */
public interface PluginHost {

    Context context();

    // ---------------------------------------------------------------- 执行 / 工具

    /** 在 Debian guest 执行命令；返回 combined stdout+stderr。 */
    default String execGuest(String command, long timeoutMs, String cwd) throws Exception {
        throw new UnsupportedOperationException("E_BRIDGE: 宿主未提供 exec");
    }

    /** 调用宿主工具（内置或其它插件注册的）；返回文本结果。 */
    default String invokeTool(String callerPluginId, String tool, String argsJson, long timeoutMs)
            throws Exception {
        throw new UnsupportedOperationException("E_BRIDGE: 宿主未提供 tool.invoke");
    }

    default JSONArray listTools() {
        return new JSONArray();
    }

    /**
     * 展示型工具卡（{@code tool.invoke({show:true})}）：只进对话流给人看，
     * 不写 jsonl 转录、不参与模型上下文。
     */
    default void showToolCard(String pluginId, String tool, String argsJson,
                              String result, boolean ok) {
    }

    // ---------------------------------------------------------------- UI / 系统

    default void toast(String text) {
    }

    /** 阻塞式确认框；返回 "ok" / "cancel"，超时返回 null。 */
    default String confirm(String title, String text, long timeoutMs) {
        return null;
    }

    /** 权限网关弹窗；返回 "once" / "always" / "deny"，超时返回 null。 */
    default String promptGrant(HookManifest m, String capability, String label,
                               String scope, String reason, long timeoutMs) {
        return null;
    }

    /** 系统特殊权限（所有文件访问 / 悬浮窗）当前是否已授予。 */
    default boolean specialGranted(String capability) {
        return false;
    }

    /** 跳转系统设置页申请特殊权限；返回是否成功跳到设置。 */
    default boolean openSpecialSettings(String capability) {
        return false;
    }

    default String androidPermissionStatus(String permission) {
        return "unsupported";
    }

    /** 申请系统运行时权限；返回 "granted" / "denied" / "timeout"。 */
    default String requestAndroidPermission(String permission, long timeoutMs) {
        return "denied";
    }

    /** 一次申请多个运行时权限；任一授予即返回 granted（定位的精确/粗略两档）。 */
    default String requestAndroidPermissions(String[] permissions, long timeoutMs) {
        if (permissions == null || permissions.length == 0) return "denied";
        return requestAndroidPermission(permissions[0], timeoutMs);
    }

    /** 定位服务是否开启（不检查权限）。 */
    default boolean locationAvailable() {
        return false;
    }

    /** {serviceEnabled, providers, fineGranted, coarseGranted}。 */
    default JSONObject locationStatus() {
        return new JSONObject();
    }

    /**
     * 读取一次位置。阻塞到拿到定位或超时；实现方负责申请系统定位权限。
     * 返回 {latitude, longitude, accuracy, altitude, speed, bearing, time, provider, stale}。
     */
    default JSONObject locationGet(long timeoutMs, boolean highAccuracy) throws Exception {
        throw new UnsupportedOperationException("E_BRIDGE: 宿主未提供定位");
    }

    default boolean notify(String title, String text) {
        return false;
    }

    default String clipboardGet() {
        return "";
    }

    default boolean clipboardSet(String text) {
        return false;
    }

    // ---------------------------------------------------------------- 导航 / 视图

    default boolean navPush(String page, JSONObject params) {
        return false;
    }

    default boolean navBack() {
        return false;
    }

    /** 当前插件页：{page, params}；在聊天页返回 null。 */
    default JSONObject navCurrent() {
        return null;
    }

    default boolean patchNode(String id, JSONObject props) {
        return false;
    }

    /**
     * 带调用方 pluginId 的 patch：实现方应只允许改自己的插槽/页面/浮层。
     * 默认退回旧方法，方便无界面宿主。
     */
    default boolean patchNode(String callerPluginId, String id, JSONObject props) {
        return patchNode(id, props);
    }

    /** 节点在窗口内的位置（dp）：{x,y,w,h}；找不到返回 null。 */
    default JSONObject boundsNode(String id) {
        return null;
    }

    /** 带调用方 pluginId 的 bounds：同样只允许查询自己的界面。 */
    default JSONObject boundsNode(String callerPluginId, String id) {
        return boundsNode(id);
    }

    // ---------------------------------------------------------------- 浮层

    default void overlayShow(String pluginId, String overlayId, JSONObject spec) {
    }

    default void overlayUpdate(String pluginId, String overlayId, JSONObject spec) {
    }

    default void overlayHide(String pluginId, String overlayId) {
    }

    default void overlayHideAll(String pluginId) {
    }

    default String systemOverlayStatus() {
        return "unknown";
    }

    default boolean systemOverlayRequest() {
        return false;
    }

    default void systemOverlayShow(String pluginId, JSONObject spec) {
    }

    default void systemOverlayHide(String pluginId) {
    }

    // ---------------------------------------------------------------- adb / 环境

    /** "shizuku" | "adb" | "none"；只表示 provider 是否存在，不代表已授权。 */
    default String adbProvider() {
        return "none";
    }

    /** 当前 provider 是否可用（Shizuku 已运行且已授权）。 */
    default boolean adbAvailable() {
        return !"none".equals(adbProvider());
    }

    /** Shizuku binder 是否在线（用于区分“未运行”和“未授权”）。 */
    default boolean adbBinderAlive() {
        return false;
    }

    /** 请求 Shizuku 授权；已授权返回 true。 */
    default boolean adbRequestPermission() {
        return false;
    }

    default String adbShell(String command, long timeoutMs) throws Exception {
        throw new UnsupportedOperationException("E_BRIDGE: 没有可用的 adb provider");
    }

    default String adbExec(String program, String argsJson, long timeoutMs) throws Exception {
        throw new UnsupportedOperationException("E_BRIDGE: 没有可用的 adb provider");
    }

    default File workspaceDir() {
        return new File(context().getFilesDir(), "workspace");
    }

    default File sharedDir() {
        return android.os.Environment.getExternalStorageDirectory();
    }

    default boolean storageAccessGranted() {
        // Environment.isExternalStorageManager() 是 API 30；低版本没有这个特殊权限。
        return android.os.Build.VERSION.SDK_INT < 30
                || android.os.Environment.isExternalStorageManager();
    }

    default boolean isAgentBusy() {
        return false;
    }

    default boolean isForeground() {
        return true;
    }

    /** 插件被禁用/重载前清掉它的浮层与页面。 */
    default void onPluginDisabled(String pluginId) {
    }
}
