package com.dsh.android.ui.framework;

/**
 * 导航外壳的宿主注册点：插件 UI 里的 {@code nav.list} 节点通过它拿模型、发起跳转。
 *
 * 只保存两个回调，不持有 Activity；未安装（例如独立 demo 页面）时 {@link #model()}
 * 返回空模型、{@link #open(String)} 是安全 no-op。
 */
public final class NavShell {

    public interface Provider {
        NavModel model();
    }

    public interface Host {
        /** 打开页面；{@link NavModel#CHAT_PAGE} 表示回到聊天。 */
        void open(String page);
    }

    private static volatile Provider provider;
    private static volatile Host host;
    private static volatile String currentPage = NavModel.CHAT_PAGE;

    private NavShell() {
    }

    public static void install(Provider provider, Host host) {
        NavShell.provider = provider;
        NavShell.host = host;
    }

    public static NavModel model() {
        Provider p = provider;
        if (p == null) return NavModel.empty();
        try {
            NavModel m = p.model();
            return m == null ? NavModel.empty() : m;
        } catch (Throwable e) {
            return NavModel.empty();
        }
    }

    public static void open(String page) {
        Host h = host;
        if (h != null && page != null && !page.isEmpty()) h.open(page);
    }

    public static String currentPage() {
        return currentPage;
    }

    public static void setCurrentPage(String page) {
        currentPage = page == null || page.isEmpty() ? NavModel.CHAT_PAGE : page;
    }
}
