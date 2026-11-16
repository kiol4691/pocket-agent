package com.quickjs;

import java.io.Closeable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JSContext extends JSObject implements Closeable {
    final QuickJS quickJS;
    final long contextPtr;
    final Set<Plugin> plugins = Collections.synchronizedSet(new HashSet<>());
    /**
     * 该 context 里创建过的所有 JSValue 原生引用。
     *
     * 必须是**强引用**：早先用 WeakHashMap，Java 包装对象被 GC 后条目消失，但原生
     * JS_DupValue 的引用还在（finalize 只是把释放排进 releaseObjPtrPool，不保证在
     * releaseRuntime 之前跑到），于是 JS_FreeRuntime 的
     * {@code list_empty(&rt->gc_obj_list)} 断言会直接 abort 进程（真机 plugin:reload
     * 崩溃就是这么来的）。强引用保证 close() 能把所有包装值逐个释放掉。
     */
    final Map<JSValue, Boolean> refs =
            Collections.synchronizedMap(new IdentityHashMap<>());
    final List<Object[]> releaseObjPtrPool = Collections.synchronizedList(new LinkedList<>());
    final Map<Integer, QuickJS.MethodDescriptor> functionRegistry = Collections.synchronizedMap(new HashMap<>());

    JSContext(QuickJS quickJS, long contextPtr) {
        super(null, quickJS.getNative()._getGlobalObject(contextPtr));
        this.quickJS = quickJS;
        this.contextPtr = contextPtr;
        this.context = this;
        QuickJS.sContextMap.put(contextPtr, this);
    }

    long getContextPtr() {
        return this.contextPtr;
    }

    void addObjRef(JSValue reference) {
        if (reference.getClass() != JSContext.class) {
            refs.put(reference, Boolean.TRUE);
        }
    }

    void releaseObjRef(JSValue reference, boolean finalize) {
        if (finalize) {
            releaseObjPtrPool.add(new Object[]{reference.tag, reference.u_int32, reference.u_float64, reference.u_ptr});
        } else {
            getNative()._releasePtr(getContextPtr(), reference.tag, reference.u_int32, reference.u_float64, reference.u_ptr);
        }
        removeObjRef(reference);
    }

    private void checkReleaseObjPtrPool() {
        while (!releaseObjPtrPool.isEmpty()) {
            Object[] ptr = releaseObjPtrPool.get(0);
            getNative()._releasePtr(getContextPtr(), (long) ptr[0], (int) ptr[1], (double) ptr[2], (long) ptr[3]);
            releaseObjPtrPool.remove(0);
        }
    }

    void removeObjRef(JSValue reference) {
        refs.remove(reference);
    }


    @Override
    public void close() {
        postEventQueue(() -> {
            if (released) {
                return;
            }
            for (Plugin plugin : plugins) {
                plugin.close(JSContext.this);
            }
            plugins.clear();
            functionRegistry.clear();
            JSValue[] jsValues = refs.keySet().toArray(new JSValue[0]);
            for (JSValue it : jsValues) {
                if (it != null) {
                    try {
                        it.close();
                    } catch (Throwable ignored) {
                        // 单个值释放失败不能阻断整个 context 的清理
                    }
                }
            }
            refs.clear();
            checkReleaseObjPtrPool();
            JSContext.super.close();
            getNative()._releaseContext(contextPtr);
            QuickJS.sContextMap.remove(getContextPtr());
        });
    }

    protected Object executeScript(TYPE expectedType, String source, String fileName) throws QuickJSException {
        Object object = getNative()._executeScript(this.getContextPtr(), expectedType.value, source, fileName, QuickJS.JS_EVAL_TYPE_GLOBAL);
        QuickJS.checkException(context);
        return object;
    }

    /**
     * @return Integer/Double/Boolean/String/JSObject/JSArray/JSFunction
     */
    public Object executeScript(String source, String fileName) throws QuickJSException {
        return executeScript(JSValue.TYPE.UNKNOWN, source, fileName);
    }

    public Object executeScript(String source, String fileName, int evalType) throws QuickJSException {
        Object object = getNative()._executeScript(this.getContextPtr(), JSValue.TYPE.UNKNOWN.value, source, fileName, evalType);
        QuickJS.checkException(context);
        return object;
    }

    public Object executeModuleScript(String source, String fileName, int evalType) throws QuickJSException {
        Object object = getNative()._executeScript(this.getContextPtr(), JSValue.TYPE.UNKNOWN.value, source, fileName, QuickJS.JS_EVAL_TYPE_MODULE);
        QuickJS.checkException(context);
        return object;
    }


    public int executeIntegerScript(String source, String fileName) throws QuickJSException {
        return (int) executeScript(JSValue.TYPE.INTEGER, source, fileName);
    }

    public double executeDoubleScript(String source, String fileName) throws QuickJSException {
        return (double) executeScript(JSValue.TYPE.DOUBLE, source, fileName);
    }

    public boolean executeBooleanScript(String source, String fileName) throws QuickJSException {
        return (boolean) executeScript(JSValue.TYPE.BOOLEAN, source, fileName);
    }

    public String executeStringScript(String source, String fileName) throws QuickJSException {
        return (String) executeScript(JSValue.TYPE.STRING, source, fileName);
    }

    public void executeVoidScript(String source, String fileName) throws QuickJSException {
        executeScript(JSValue.TYPE.NULL, source, fileName);
    }

    public JSArray executeArrayScript(String source, String fileName) throws QuickJSException {
        return (JSArray) executeScript(JSValue.TYPE.JS_ARRAY, source, fileName);
    }

    public JSObject executeObjectScript(String source, String fileName) throws QuickJSException {
        return (JSObject) executeScript(JSValue.TYPE.JS_OBJECT, source, fileName);
    }

    public boolean isReleased() {
        if (getQuickJS().isReleased()) {
            return true;
        }
        return this.released;
    }

    void _registerCallback(JavaCallback callback, JSFunction functionHandle) {
        QuickJS.MethodDescriptor methodDescriptor = new QuickJS.MethodDescriptor();
        methodDescriptor.callback = callback;
        functionRegistry.put(callback.hashCode(), methodDescriptor);
    }

    void _registerCallback(JavaVoidCallback callback, JSFunction functionHandle) {
        QuickJS.MethodDescriptor methodDescriptor = new QuickJS.MethodDescriptor();
        methodDescriptor.voidCallback = callback;
        functionRegistry.put(callback.hashCode(), methodDescriptor);
    }

    void checkRuntime(JSValue value) {
        if (value != null && !value.isUndefined()) {
            if (value.context == null) {
                throw new Error("Invalid target runtime");
            }
            QuickJS quickJS = value.context.quickJS;
            if (quickJS == null || quickJS.isReleased() || quickJS != this.quickJS) {
                throw new Error("Invalid target runtime");
            }
        }
    }

    public void addPlugin(Plugin plugin) {
        checkReleased();
        if (plugins.contains(plugin)) {
            return;
        }
        plugin.setup(context);
        this.plugins.add(plugin);
    }

    void checkReleased() {
        checkReleaseObjPtrPool();
        if (this.isReleased()) {
            throw new Error("Context disposed error");
        }
    }

    public QuickJSNative getNative() {
        return quickJS.getNative();
    }

    public QuickJS getQuickJS() {
        return quickJS;
    }
}
