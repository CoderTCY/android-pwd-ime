package com.pwdime.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.util.SparseArray;
import android.view.inputmethod.EditorInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Vector / libxposed API 102 模块：全系统密码框自动切换密码输入法（不改写 default_input_method 持久化设置）。
 *
 * 作用域：系统框架（META-INF/xposed/scope.list 中的 system）。
 *
 * 配置（无硬编码默认值，由配置 App 写入 Settings.Secure，立即生效）：
 *   pwdime_ime_id           密码输入法 ID（ComponentName#flattenToShortString()）
 *   pwdime_enabled          1/0 总开关，缺省视为开
 *   pwdime_terminal_enabled 1/0 终端视图自动切换开关，缺省视为开
 *
 * 原理：
 *   1. Hook IMMS 的 startInputOrWindowGainedFocus / showSoftInput / showCurrentInputInternal：
 *      - EditorInfo.inputType 命中密码变体或终端特征 → 记住当前常规输入法，调用 setInputMethodLocked
 *        切到密码输入法；
 *      - 非目标输入框 → 若当前是密码输入法则恢复记住的常规输入法。
 *   2. 虚拟化 InputMethodSettings 读写：
 *      - 本模块触发 setInputMethodLocked 期间（ThreadLocal mTempSwitching），拦截
 *        putSelectedInputMethod / putSelectedSubtype / saveCurrentInputMethodAndSubtypeToHistory /
 *        putSelectedDefaultDeviceInputMethod，仅更新内存覆盖态而不落盘到 Settings.Secure；
 *      - 拦截 InputMethodSettings.getSelectedInputMethod / getSelectedInputMethodSubtypeHashCode，
 *        在覆盖态期间返回内存值；用户手动切走或外部修改设置时自动清除覆盖态并放行落盘。
 *   3. 支持 API 102 热重载（autoHotReload=true）：通过 onHotReloading / onHotReloaded 与
 *      HookBuilder#setId 原子替换全部 hook 并跨代迁移运行状态。
 *
 * 适配 Android 16 (API 36)：签名已对照 AOSP android-16.0.0_r1 与 PLF110 ColorOS
 * services.jar / oplus-services.jar 核实。
 */
public final class PwdImeSwitcher extends XposedModule {

    private static final String TAG = "PwdImeSwitcher";

    private static final String IMMS_CLASS =
            "com.android.server.inputmethod.InputMethodManagerService";
    private static final String IMF_LOCK_CLASS =
            "com.android.server.inputmethod.ImfLock";
    private static final String SETTINGS_REPO_CLASS =
            "com.android.server.inputmethod.InputMethodSettingsRepository";
    private static final String IM_SETTINGS_CLASS =
            "com.android.server.inputmethod.InputMethodSettings";

    private static final String KEY_IME_ID = "pwdime_ime_id";
    private static final String KEY_ENABLED = "pwdime_enabled";
    private static final String KEY_TERMINAL_ENABLED = "pwdime_terminal_enabled";

    private static final String STATE_CLASS_LOADER = "classLoader";
    private static final String STATE_SAVED_IME = "savedImeId";
    private static final String STATE_SAVED_SUBTYPE = "savedSubtypeIndex";
    private static final String STATE_OVERRIDE_IME = "overrideImeId";
    private static final String STATE_OVERRIDE_SUBTYPE = "overrideSubtypeHash";

    /** InputMethodUtils.NOT_A_SUBTYPE_INDEX */
    private static final int NOT_A_SUBTYPE_INDEX = -1;

    /** 配置缓存有效期：settings provider 与本模块同进程，读取极廉价，2s 缓存足够 */
    private static final long CONFIG_TTL_MS = 2000;

    private static final class Config {
        final boolean enabled;
        final boolean terminalEnabled;
        final String imeId;
        final long loadedAt;

        Config(boolean enabled, boolean terminalEnabled, String imeId, long loadedAt) {
            this.enabled = enabled;
            this.terminalEnabled = terminalEnabled;
            this.imeId = imeId;
            this.loadedAt = loadedAt;
        }
    }

    private static final class TargetImeEntry {
        final String pkg;
        final int uid;

        TargetImeEntry(String pkg, int uid) {
            this.pkg = pkg;
            this.uid = uid;
        }
    }

    private static final class MethodKey {
        final Class<?> clazz;
        final String name;
        final boolean isStatic;
        final Class<?>[] argTypes;
        final int hash;

        MethodKey(Class<?> clazz, String name, boolean isStatic, Class<?>[] argTypes) {
            this.clazz = clazz;
            this.name = name;
            this.isStatic = isStatic;
            this.argTypes = argTypes;
            int h = System.identityHashCode(clazz);
            h = 31 * h + name.hashCode();
            h = 31 * h + (isStatic ? 1 : 0);
            for (Class<?> t : argTypes) {
                h = 31 * h + (t == null ? 0 : System.identityHashCode(t));
            }
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MethodKey)) {
                return false;
            }
            MethodKey other = (MethodKey) obj;
            if (clazz != other.clazz || isStatic != other.isStatic
                    || !name.equals(other.name) || argTypes.length != other.argTypes.length) {
                return false;
            }
            for (int i = 0; i < argTypes.length; i++) {
                if (argTypes[i] != other.argTypes[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** userId -> 进入密码框前正在使用的常规输入法 ID */
    private final SparseArray<String> mSavedImeId = new SparseArray<>();
    /** userId -> 进入密码框前的常规输入法 subtypeIndex */
    private final SparseArray<Integer> mSavedSubtypeIndex = new SparseArray<>();
    /** userId -> 临时切换期间内存虚拟化的当前输入法 ID */
    private final SparseArray<String> mOverrideImeId = new SparseArray<>();
    /** userId -> 临时切换期间内存虚拟化的当前 subtype hashCode */
    private final SparseArray<Integer> mOverrideSubtypeHash = new SparseArray<>();
    /** userId -> 配置缓存 */
    private final SparseArray<Config> mConfigCache = new SparseArray<>();

    /** 专用于保护上述内存表与配置缓存的细粒度锁（严禁持锁调用 IMMS） */
    private final Object mStateLock = new Object();

    /** 标记当前线程正在由本模块执行临时切换（避免持久化写入 Settings.Secure） */
    private final ThreadLocal<Boolean> mTempSwitching =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** 临时绕过 InputMethodSettings 内存覆盖，读取真实持久化设置 */
    private final ThreadLocal<Boolean> mBypassOverride =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** 防止 ColorOS AppStatusManager Hook 重入 */
    private final ThreadLocal<Boolean> mInOsenseHook =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ConcurrentHashMap<String, Field> mFieldCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MethodKey, Method> mMethodCache = new ConcurrentHashMap<>();

    private volatile ClassLoader mSystemClassLoader;
    private Context mContext;
    private ContentResolver mResolver;
    private Class<?> mImfLockClass;
    private Class<?> mSettingsRepoClass;
    private Field mSettingsUserIdField;
    private Object mHansManager;
    private boolean mHansLookupDone;
    private int mStatusInputId = Integer.MIN_VALUE;
    private Object mStatusInputType;
    private final SparseArray<String> mTargetPkgCache = new SparseArray<>();
    private final SparseArray<Integer> mTargetUidCache = new SparseArray<>();
    private boolean mWarnedNoConfig;
    private boolean mWarnedNotEnabled;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        if (!param.isSystemServer()) {
            detach();
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        ClassLoader cl = param.getClassLoader();
        mSystemClassLoader = cl;
        try {
            init(cl, false);
        } catch (Throwable t) {
            logError("init failed", t);
        }
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        // 仅放入 boot ClassLoader 类型（HashMap/String/Integer/ClassLoader），
        // 绝不携带模块自定义类实例，以免阻止旧世代 ClassLoader 回收或被框架拒绝。
        HashMap<String, Object> state = new HashMap<>(5);
        state.put(STATE_CLASS_LOADER, mSystemClassLoader);
        synchronized (mStateLock) {
            state.put(STATE_SAVED_IME, copyStringSparseArray(mSavedImeId));
            state.put(STATE_SAVED_SUBTYPE, copyIntSparseArray(mSavedSubtypeIndex));
            state.put(STATE_OVERRIDE_IME, copyStringSparseArray(mOverrideImeId));
            state.put(STATE_OVERRIDE_SUBTYPE, copyIntSparseArray(mOverrideSubtypeHash));
        }
        param.setSavedInstanceState(state);
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        if (!param.isSystemServer()) {
            for (HookHandle old : param.getOldHookHandles()) {
                old.unhook();
            }
            detach();
            return;
        }
        ClassLoader cl = restoreHotReloadState(param);
        mSystemClassLoader = cl;
        if (cl != null) {
            try {
                init(cl, true);
            } catch (Throwable t) {
                logError("hot reload init failed", t);
            }
        } else {
            logWarn("hot reload skipped init: system ClassLoader unavailable");
        }
        for (HookHandle old : param.getOldHookHandles()) {
            old.unhook();
        }
    }

    private ClassLoader restoreHotReloadState(HotReloadedParam param) {
        ClassLoader cl = null;
        Object rawState = param.getSavedInstanceState();
        if (rawState instanceof Map<?, ?>) {
            Map<?, ?> state = (Map<?, ?>) rawState;
            Object rawCl = state.get(STATE_CLASS_LOADER);
            if (rawCl instanceof ClassLoader) {
                cl = (ClassLoader) rawCl;
            }
            synchronized (mStateLock) {
                restoreStringSparseArray(state.get(STATE_SAVED_IME), mSavedImeId);
                restoreIntSparseArray(state.get(STATE_SAVED_SUBTYPE), mSavedSubtypeIndex);
                restoreStringSparseArray(state.get(STATE_OVERRIDE_IME), mOverrideImeId);
                restoreIntSparseArray(state.get(STATE_OVERRIDE_SUBTYPE), mOverrideSubtypeHash);
            }
        }
        if (cl == null) {
            for (HookHandle old : param.getOldHookHandles()) {
                ClassLoader candidate = old.getExecutable().getDeclaringClass().getClassLoader();
                if (candidate != null) {
                    cl = candidate;
                    break;
                }
            }
        }
        return cl;
    }

    private static HashMap<Integer, String> copyStringSparseArray(SparseArray<String> src) {
        HashMap<Integer, String> out = new HashMap<>(src.size());
        for (int i = 0; i < src.size(); i++) {
            out.put(src.keyAt(i), src.valueAt(i));
        }
        return out;
    }

    private static HashMap<Integer, Integer> copyIntSparseArray(SparseArray<Integer> src) {
        HashMap<Integer, Integer> out = new HashMap<>(src.size());
        for (int i = 0; i < src.size(); i++) {
            out.put(src.keyAt(i), src.valueAt(i));
        }
        return out;
    }

    private static void restoreStringSparseArray(Object raw, SparseArray<String> dst) {
        dst.clear();
        if (!(raw instanceof Map<?, ?>)) {
            return;
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof Integer && e.getValue() instanceof String) {
                dst.put((Integer) e.getKey(), (String) e.getValue());
            }
        }
    }

    private static void restoreIntSparseArray(Object raw, SparseArray<Integer> dst) {
        dst.clear();
        if (!(raw instanceof Map<?, ?>)) {
            return;
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof Integer && e.getValue() instanceof Integer) {
                dst.put((Integer) e.getKey(), (Integer) e.getValue());
            }
        }
    }

    private void init(ClassLoader cl, boolean hotReload) throws Exception {
        Class<?> imms = findClass(IMMS_CLASS, cl);
        try {
            mImfLockClass = findClass(IMF_LOCK_CLASS, cl);
        } catch (Throwable t) {
            logWarn("ImfLock not found, falling back to object lock: " + t);
        }
        mSettingsRepoClass = findClass(SETTINGS_REPO_CLASS, cl);
        Class<?> imSettings = findClass(IM_SETTINGS_CLASS, cl);
        mSettingsUserIdField = findField(imSettings, "mUserId");

        int hooked = hookAllMethods(imms, "startInputOrWindowGainedFocus",
                "imms.startInputOrWindowGainedFocus", chain -> {
                    try {
                        handleFocusEvent(chain.getThisObject(),
                                (Method) chain.getExecutable(), chain.getArgs());
                    } catch (Throwable t) {
                        // 绝不让模块异常泄漏进 system_server 的原方法
                        logError("handle error", t);
                    }
                    return chain.proceed();
                });

        // 补充路径：密码框已聚焦（无焦点变化）时再点它只走 showSoftInput，
        // 不触发 startInputOrWindowGainedFocus。hook public binder 入口
        // IMMS.showSoftInput（虚调用不内联；private showSoftInputLocked 在
        // AOT 下被内联，hook 不生效）。
        int hookedShow = hookAllMethods(imms, "showSoftInput",
                "imms.showSoftInput", chain -> {
                    try {
                        handleShowInput(chain.getThisObject());
                    } catch (Throwable t) {
                        logError("handle error(show)", t);
                    }
                    return chain.proceed();
                });

        // A16 实测：点击已聚焦输入框的显示由 WMS/InsetsController 系统侧发起，
        // 经 registerImeRequestedChangedListener 回调 showCurrentInputInternal，
        // 不经过任何 binder 入口。此处是覆盖该场景的必需 hook 点。
        int hookedInternal = hookAllMethods(imms, "showCurrentInputInternal",
                "imms.showCurrentInputInternal", chain -> {
                    try {
                        handleInternalShow(chain.getThisObject(), chain.getArgs());
                    } catch (Throwable t) {
                        logError("handle error(internal)", t);
                    }
                    return chain.proceed();
                });

        int hookedSettings = hookInputMethodSettings(imms, imSettings);
        int hookedExt = hookColorOsExt(cl);
        int hookedOsense = hookColorOsImeExemption(cl);

        logInfo("active in system_server (API " + getApiVersion()
                + ", hotReload=" + hotReload
                + "), hooked=" + hooked
                + ", hookedShow=" + hookedShow
                + ", hookedInternal=" + hookedInternal
                + ", hookedSettings=" + hookedSettings
                + ", hookedExt=" + hookedExt
                + ", hookedOsense=" + hookedOsense
                + " (non-persistent mode, config via Settings.Secure: " + KEY_IME_ID + ")");
        if (hooked == 0 || hookedShow == 0 || hookedInternal == 0 || hookedSettings < 6) {
            logWarn("FATAL: expected methods not found ("
                    + hooked + "/" + hookedShow + "/" + hookedInternal + "/" + hookedSettings
                    + "), ROM signature mismatch?");
        }
    }

    /**
     * ColorOS 适配：InputMethodManagerServiceExtImpl#notifyImeAttributeChanged 会从
     * OplusInputMethodSettingsHelper.mDefaultInputMethod（直读 Settings.Secure）比对
     * 当前 bindingController.getSelectedMethodId()，不一致则强制回切为系统默认 IME。
     * 在非持久化切换期间临时覆盖 mDefaultInputMethod，并在方法返回后立即还原。
     */
    private int hookColorOsExt(ClassLoader cl) {
        Class<?> extImpl = findClassIfExists(
                "com.android.server.inputmethod.InputMethodManagerServiceExtImpl", cl);
        if (extImpl == null) {
            return 0;
        }
        return hookAllMethods(extImpl, "notifyImeAttributeChanged",
                "ext.notifyImeAttributeChanged", chain -> {
                    Object helper = null;
                    Object prev = null;
                    boolean swapped = false;
                    try {
                        Object service = getObjectField(chain.getThisObject(), "mService");
                        Object wrapper = callMethod(service, "getWrapper");
                        int userId = (Integer) callMethod(wrapper, "getCurrentImeUserId");
                        String override;
                        synchronized (mStateLock) {
                            override = mOverrideImeId.get(userId);
                        }
                        if (override != null) {
                            helper = getObjectField(chain.getThisObject(), "mSettingsHelper");
                            prev = getObjectField(helper, "mDefaultInputMethod");
                            setObjectField(helper, "mDefaultInputMethod", override);
                            swapped = true;
                        }
                    } catch (Throwable t) {
                        logError("ext before error", t);
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        if (swapped) {
                            try {
                                setObjectField(helper, "mDefaultInputMethod", prev);
                            } catch (Throwable t) {
                                logError("ext after error", t);
                            }
                        }
                    }
                });
    }

    /**
     * ColorOS 适配：Osense / Hans 从 Settings.Secure.default_input_method 同步
     * STATUS_INPUT 白名单；非持久化模式下 default_input_method 保持原默认输入法，导致
     * 目标输入法（如小企鹅）会被当成普通浮窗/后台应用而被 Hans 每 ~2 秒冻结或被
     * boot_clear 查杀。此处在 AppStatusManager 与 OplusHansDBConfig 读取侧放行配置
     * 的目标输入法，使其与默认输入法享有同等的输入法防冻结/防杀保护。
     */
    @SuppressWarnings("unchecked")
    private int hookColorOsImeExemption(ClassLoader cl) {
        Class<?> statusTypeClass = findClassIfExists(
                "com.android.server.oplus.osense.OsenseConstants$AppStatusType", cl);
        Class<?> appStatusMgrClass = findClassIfExists(
                "com.android.server.oplus.osense.resource.AppStatusManager", cl);
        if (statusTypeClass != null) {
            try {
                mStatusInputType = getStaticObjectField(statusTypeClass, "STATUS_INPUT");
                mStatusInputId = (Integer) callMethod(mStatusInputType, "getId");
            } catch (Throwable t) {
                logError("resolve STATUS_INPUT failed", t);
            }
        }

        int count = 0;
        if (appStatusMgrClass != null
                && mStatusInputType != null
                && mStatusInputId != Integer.MIN_VALUE) {
            count += hookMethodSafe(appStatusMgrClass, "getAppStatus",
                    new Class<?>[]{statusTypeClass, int.class},
                    "osense.getAppStatus.typeUid",
                    chain -> {
                        Object result = chain.proceed();
                        try {
                            if (chain.getArg(0) != mStatusInputType) {
                                return result;
                            }
                            int uid = (Integer) chain.getArg(1);
                            String pkg = getConfiguredTargetPkgForUid(uid);
                            if (pkg == null) {
                                return result;
                            }
                            ArrayList<String> list = (ArrayList<String>) result;
                            if (list == null) {
                                ArrayList<String> created = new ArrayList<>(1);
                                created.add(pkg);
                                result = created;
                            } else if (!list.contains(pkg)) {
                                ArrayList<String> copy = new ArrayList<>(list);
                                copy.add(pkg);
                                result = copy;
                            }
                        } catch (Throwable t) {
                            logError("getAppStatus(type,uid) hook error", t);
                        }
                        return result;
                    });

            count += hookMethodSafe(appStatusMgrClass, "getAppStatus",
                    new Class<?>[]{statusTypeClass},
                    "osense.getAppStatus.type",
                    chain -> {
                        Object result = chain.proceed();
                        try {
                            if (chain.getArg(0) != mStatusInputType) {
                                return result;
                            }
                            SparseArray<ArrayList<String>> map =
                                    (SparseArray<ArrayList<String>>) result;
                            if (map == null) {
                                map = new SparseArray<>();
                                result = map;
                            }
                            TargetImeEntry entry = getActiveTargetIme(0);
                            if (entry != null && entry.uid > 0) {
                                ensureSparseMapContains(map, entry.uid, entry.pkg);
                            }
                        } catch (Throwable t) {
                            logError("getAppStatus(type) hook error", t);
                        }
                        return result;
                    });

            count += hookMethodSafe(appStatusMgrClass, "isAppStatusOn",
                    new Class<?>[]{int.class, int.class},
                    "osense.isAppStatusOn",
                    chain -> {
                        Object result = chain.proceed();
                        try {
                            if ((Integer) chain.getArg(0) != mStatusInputId
                                    || Boolean.TRUE.equals(result)) {
                                return result;
                            }
                            int uid = (Integer) chain.getArg(1);
                            if (getConfiguredTargetPkgForUid(uid) != null) {
                                result = Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            logError("isAppStatusOn hook error", t);
                        }
                        return result;
                    });

            count += hookMethodSafe(appStatusMgrClass, "getUidListByStatusId",
                    new Class<?>[]{int.class},
                    "osense.getUidListByStatusId",
                    chain -> {
                        Object result = chain.proceed();
                        try {
                            if ((Integer) chain.getArg(0) != mStatusInputId) {
                                return result;
                            }
                            TargetImeEntry entry = getActiveTargetIme(0);
                            if (entry == null || entry.uid <= 0) {
                                return result;
                            }
                            ArrayList<Integer> list = (ArrayList<Integer>) result;
                            if (list == null) {
                                ArrayList<Integer> created = new ArrayList<>(1);
                                created.add(entry.uid);
                                result = created;
                            } else if (!list.contains(entry.uid)) {
                                ArrayList<Integer> copy = new ArrayList<>(list);
                                copy.add(entry.uid);
                                result = copy;
                            }
                        } catch (Throwable t) {
                            logError("getUidListByStatusId hook error", t);
                        }
                        return result;
                    });

            count += hookMethodSafe(appStatusMgrClass, "getPkgNameListByStatusId",
                    new Class<?>[]{int.class},
                    "osense.getPkgNameListByStatusId",
                    chain -> {
                        Object result = chain.proceed();
                        try {
                            if ((Integer) chain.getArg(0) != mStatusInputId) {
                                return result;
                            }
                            TargetImeEntry entry = getActiveTargetIme(0);
                            if (entry == null) {
                                return result;
                            }
                            ArrayList<String> list = (ArrayList<String>) result;
                            if (list == null) {
                                ArrayList<String> created = new ArrayList<>(1);
                                created.add(entry.pkg);
                                result = created;
                            } else if (!list.contains(entry.pkg)) {
                                ArrayList<String> copy = new ArrayList<>(list);
                                copy.add(entry.pkg);
                                result = copy;
                            }
                        } catch (Throwable t) {
                            logError("getPkgNameListByStatusId hook error", t);
                        }
                        return result;
                    });
        }

        Class<?> hansDbClass = findClassIfExists("com.android.server.hans.OplusHansDBConfig", cl);
        if (hansDbClass != null) {
            count += hookMethodSafe(hansDbClass, "isDefaultInputPkg",
                    new Class<?>[]{String.class},
                    "hans.isDefaultInputPkg",
                    chain -> {
                        Object result = chain.proceed();
                        try {
                            Object arg0 = chain.getArg(0);
                            if (Boolean.TRUE.equals(result) || !(arg0 instanceof String)) {
                                return result;
                            }
                            TargetImeEntry entry = getActiveTargetIme(0);
                            if (entry != null && entry.pkg.equals(arg0)) {
                                result = Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            logError("isDefaultInputPkg hook error", t);
                        }
                        return result;
                    });
        }

        return count;
    }

    private int hookAllMethods(
            Class<?> clazz, String name, String id, XposedInterface.Hooker hooker) {
        int count = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) {
                hook(m).setId(id).intercept(hooker);
                count++;
            }
        }
        return count;
    }

    private int hookMethodSafe(
            Class<?> clazz,
            String name,
            Class<?>[] paramTypes,
            String id,
            XposedInterface.Hooker hooker) {
        try {
            Method m = findMethodExact(clazz, name, paramTypes);
            hook(m).setId(id).intercept(hooker);
            return 1;
        } catch (Throwable t) {
            logWarn("optional hook skip " + clazz.getSimpleName() + "." + name + ": " + t);
            return 0;
        }
    }

    private static void ensureSparseMapContains(
            SparseArray<ArrayList<String>> map, int uid, String pkg) {
        ArrayList<String> list = map.get(uid);
        if (list == null) {
            ArrayList<String> created = new ArrayList<>(1);
            created.add(pkg);
            map.put(uid, created);
        } else if (!list.contains(pkg)) {
            ArrayList<String> copy = new ArrayList<>(list);
            copy.add(pkg);
            map.put(uid, copy);
        }
    }

    /**
     * 虚拟化 InputMethodSettings 的当前输入法/子类型读写，使本模块触发的临时切换
     * 不修改 Settings.Secure 持久化设置（default_input_method 等）。
     */
    private int hookInputMethodSettings(Class<?> imms, Class<?> imSettings) {
        int count = 0;

        count += hookAllMethods(imSettings, "putSelectedInputMethod",
                "imSettings.putSelectedInputMethod", chain -> {
                    boolean skip = false;
                    try {
                        int userId = getSettingsUserId(chain.getThisObject());
                        String id = (String) chain.getArg(0);
                        if (Boolean.TRUE.equals(mTempSwitching.get())) {
                            synchronized (mStateLock) {
                                if (mSavedImeId.get(userId) != null) {
                                    mOverrideImeId.put(userId, id);
                                } else {
                                    mOverrideImeId.remove(userId);
                                    mOverrideSubtypeHash.remove(userId);
                                }
                            }
                            skip = true;
                        } else {
                            // 用户或系统主动切换输入法：清除临时覆盖并放行落盘
                            clearOverrideAndSaved(userId);
                        }
                    } catch (Throwable t) {
                        logError("putSelectedInputMethod hook error", t);
                    }
                    if (skip) {
                        return null;
                    }
                    return chain.proceed();
                });

        count += hookAllMethods(imSettings, "putSelectedSubtype",
                "imSettings.putSelectedSubtype", chain -> {
                    boolean skip = false;
                    try {
                        int userId = getSettingsUserId(chain.getThisObject());
                        int hashCode = (Integer) chain.getArg(0);
                        if (Boolean.TRUE.equals(mTempSwitching.get())) {
                            synchronized (mStateLock) {
                                if (mSavedImeId.get(userId) != null) {
                                    mOverrideSubtypeHash.put(userId, hashCode);
                                } else {
                                    mOverrideSubtypeHash.remove(userId);
                                }
                            }
                            skip = true;
                        } else {
                            synchronized (mStateLock) {
                                mOverrideSubtypeHash.remove(userId);
                            }
                        }
                    } catch (Throwable t) {
                        logError("putSelectedSubtype hook error", t);
                    }
                    if (skip) {
                        return null;
                    }
                    return chain.proceed();
                });

        XposedInterface.Hooker skipOnTempHook = chain -> {
            boolean skip = false;
            try {
                if (Boolean.TRUE.equals(mTempSwitching.get())) {
                    skip = true;
                }
            } catch (Throwable t) {
                logError("skipOnTemp hook error", t);
            }
            if (skip) {
                return null;
            }
            return chain.proceed();
        };
        count += hookAllMethods(imSettings, "saveCurrentInputMethodAndSubtypeToHistory",
                "imSettings.saveHistory", skipOnTempHook);
        count += hookAllMethods(imSettings, "putSelectedDefaultDeviceInputMethod",
                "imSettings.putDefaultDeviceIme", skipOnTempHook);

        count += hookAllMethods(imSettings, "getSelectedInputMethod",
                "imSettings.getSelectedInputMethod", chain -> {
                    String override = null;
                    try {
                        if (!Boolean.TRUE.equals(mBypassOverride.get())) {
                            int userId = getSettingsUserId(chain.getThisObject());
                            synchronized (mStateLock) {
                                override = mOverrideImeId.get(userId);
                            }
                        }
                    } catch (Throwable t) {
                        logError("getSelectedInputMethod hook error", t);
                    }
                    if (override != null) {
                        return override;
                    }
                    return chain.proceed();
                });

        count += hookAllMethods(imSettings, "getSelectedInputMethodSubtypeHashCode",
                "imSettings.getSelectedSubtypeHash", chain -> {
                    Integer overrideHash = null;
                    try {
                        if (!Boolean.TRUE.equals(mBypassOverride.get())) {
                            int userId = getSettingsUserId(chain.getThisObject());
                            synchronized (mStateLock) {
                                overrideHash = mOverrideSubtypeHash.get(userId);
                            }
                        }
                    } catch (Throwable t) {
                        logError("getSelectedSubtype hook error", t);
                    }
                    if (overrideHash != null) {
                        return overrideHash;
                    }
                    return chain.proceed();
                });

        // 外部（如 adb / 系统设置）直接修改 Settings.Secure.default_input_method 时清除覆盖
        count += hookAllMethods(imms, "updateInputMethodsFromSettingsLocked",
                "imms.updateFromSettings", chain -> {
                    try {
                        int userId = (Integer) chain.getArg(1);
                        String saved;
                        String override;
                        synchronized (mStateLock) {
                            saved = mSavedImeId.get(userId);
                            override = mOverrideImeId.get(userId);
                        }
                        if (override != null && saved != null) {
                            String realDefault = readRealSelectedIme(userId);
                            if (!Objects.equals(saved, realDefault)) {
                                clearOverrideAndSaved(userId);
                            }
                        }
                    } catch (Throwable t) {
                        logError("settings sync check error", t);
                    }
                    return chain.proceed();
                });

        return count;
    }

    private int getSettingsUserId(Object settings) throws IllegalAccessException {
        return mSettingsUserIdField.getInt(settings);
    }

    private void clearOverrideAndSaved(int userId) {
        synchronized (mStateLock) {
            mOverrideImeId.remove(userId);
            mOverrideSubtypeHash.remove(userId);
            mSavedImeId.remove(userId);
            mSavedSubtypeIndex.remove(userId);
        }
    }

    private String readRealSelectedIme(int userId) {
        Object settings = callStaticMethod(mSettingsRepoClass, "get", userId);
        mBypassOverride.set(Boolean.TRUE);
        try {
            return (String) callMethod(settings, "getSelectedInputMethod");
        } finally {
            mBypassOverride.set(Boolean.FALSE);
        }
    }

    /**
     * showCurrentInputInternal 路径（系统侧发起的 IME 显示，如点击已聚焦输入框）。
     * userId 从 windowToken 经 resolveImeUserIdFromWindowLocked 解析；
     * 该方法自身持 ImfLock，enforce 内部再次 synchronized 无害（可重入）。
     * 编辑器信息同样优先 mCurEditorInfo。
     */
    private void handleInternalShow(Object imms, List<Object> args) throws Exception {
        Object windowToken = args.get(0);
        int userId = (Integer) callMethod(imms,
                "resolveImeUserIdFromWindowLocked", windowToken);
        enforce(imms, getActiveEditorInfo(imms, userId), userId);
    }

    /**
     * showSoftInput 路径：public binder 入口无 userId 参数，从调用 uid 解析
     * （系统发起的 show 解析为 user 0，单用户设备正确，多用户下为已知边界）。
     * before-hook 运行于原方法 synchronized(ImfLock) 之前，enforce 自行持锁。
     */
    private void handleShowInput(Object imms) throws Exception {
        int uid = Binder.getCallingUid();
        int userId = (Integer) callStaticMethod(UserHandle.class, "getUserId", uid);
        enforce(imms, getActiveEditorInfo(imms, userId), userId);
    }

    /**
     * 编辑器优先取 userData.mCurEditorInfo（同窗口内换输入框时持续更新），
     * 为空才回退 mImeBindingState.mFocusedWindowEditorInfo（仅窗口焦点变化时更新）。
     */
    private EditorInfo getActiveEditorInfo(Object imms, int userId) {
        Object userData = callMethod(imms, "getUserData", userId);
        EditorInfo editorInfo = (EditorInfo) getObjectField(userData, "mCurEditorInfo");
        if (editorInfo == null) {
            Object bindingState = getObjectField(userData, "mImeBindingState");
            editorInfo = (EditorInfo) getObjectField(bindingState, "mFocusedWindowEditorInfo");
        }
        return editorInfo;
    }

    private void handleFocusEvent(Object imms, Method method, List<Object> args)
            throws Exception {
        EditorInfo editorInfo = null;
        int userId = 0;
        Class<?>[] types = method.getParameterTypes();
        int lastInt = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) {
                lastInt = i; // Android 11~16 该方法最后一个 int 参数即 @UserIdInt userId
            } else if (types[i] == EditorInfo.class && args.get(i) != null) {
                editorInfo = (EditorInfo) args.get(i);
            }
        }
        if (lastInt >= 0) {
            userId = (Integer) args.get(lastInt);
        }
        enforce(imms, editorInfo, userId);
    }

    /** 核心策略：按编辑器是否密码框临时切换/恢复输入法（须在 ImfLock 临界区内调用） */
    private void enforce(Object imms, EditorInfo editorInfo, int userId) throws Exception {
        Config cfg = getConfig(imms, userId);
        if (cfg == null) {
            return;
        }
        if (!cfg.enabled) {
            boolean hasSaved;
            synchronized (mStateLock) {
                hasSaved = mSavedImeId.get(userId) != null;
            }
            if (!hasSaved) {
                return;
            }
        }
        String pwdImeId = cfg.imeId;
        if (pwdImeId.isEmpty()) {
            if (!mWarnedNoConfig) {
                mWarnedNoConfig = true;
                logWarn("no password IME configured, open the PwdImeSwitcher app to select one");
            }
            return;
        }
        boolean isPwd = cfg.enabled && editorInfo != null && isPasswordType(editorInfo.inputType);
        boolean isTerm = cfg.enabled && cfg.terminalEnabled
                && editorInfo != null && isTerminalType(editorInfo);
        boolean shouldSwitch = isPwd || isTerm;

        Object lock = mImfLockClass != null ? mImfLockClass : this;
        synchronized (lock) {
            Object settings = callStaticMethod(mSettingsRepoClass, "get", userId);
            String current = (String) callMethod(settings, "getSelectedInputMethod");
            if (current == null) {
                return;
            }
            String saved;
            Integer savedSubtypeObj;
            synchronized (mStateLock) {
                saved = mSavedImeId.get(userId);
                savedSubtypeObj = mSavedSubtypeIndex.get(userId);
            }
            if (shouldSwitch) {
                if (pwdImeId.equals(current)) {
                    return;
                }
                if (!isImeEnabled(settings, pwdImeId)) {
                    if (!mWarnedNotEnabled) {
                        mWarnedNotEnabled = true;
                        logWarn("configured IME not installed/enabled: "
                                + pwdImeId + " — enable it in system IME settings first");
                    }
                    return;
                }
                if (saved == null) {
                    int curSubtypeIdx = (Integer) callMethod(
                            settings, "getSelectedInputMethodSubtypeIndex", current);
                    synchronized (mStateLock) {
                        mSavedImeId.put(userId, current);
                        mSavedSubtypeIndex.put(userId, curSubtypeIdx);
                    }
                }
                unfreezeTargetIme(pwdImeId, userId);
                callTempSetInputMethodLocked(imms, pwdImeId, NOT_A_SUBTYPE_INDEX, userId);
                logInfo((isPwd ? "password" : "terminal")
                        + " field -> temp switched to " + pwdImeId);
            } else if (saved != null && pwdImeId.equals(current)) {
                int restoreSubtype = savedSubtypeObj != null ? savedSubtypeObj : NOT_A_SUBTYPE_INDEX;
                synchronized (mStateLock) {
                    mSavedImeId.remove(userId);
                    mSavedSubtypeIndex.remove(userId);
                }
                callTempSetInputMethodLocked(imms, saved, restoreSubtype, userId);
                logInfo("left target field -> restored " + saved);
            }
        }
    }

    private void callTempSetInputMethodLocked(
            Object imms, String imeId, int subtypeIndex, int userId) {
        mTempSwitching.set(Boolean.TRUE);
        try {
            callMethod(imms, "setInputMethodLocked", imeId, subtypeIndex, userId);
        } finally {
            mTempSwitching.set(Boolean.FALSE);
        }
    }

    private Context ensureContext(Object imms) {
        Context ctx = mContext;
        if (ctx != null) {
            return ctx;
        }
        if (imms != null) {
            try {
                ctx = (Context) getObjectField(imms, "mContext");
            } catch (Throwable ignored) {
            }
        }
        if (ctx == null) {
            try {
                Class<?> at = findClassIfExists("android.app.ActivityThread", null);
                if (at != null) {
                    Object current = callStaticMethod(at, "currentActivityThread");
                    if (current != null) {
                        ctx = (Context) callMethod(current, "getSystemContext");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (ctx != null) {
            mContext = ctx;
        }
        return ctx;
    }

    private static String extractPkg(String imeId) {
        if (imeId == null || imeId.isEmpty()) {
            return null;
        }
        int slash = imeId.indexOf('/');
        String pkg = slash > 0 ? imeId.substring(0, slash) : imeId;
        return pkg.isEmpty() ? null : pkg;
    }

    private int resolveTargetUid(String pkg, int userId) {
        if (pkg == null) {
            return -1;
        }
        synchronized (mStateLock) {
            String cachedPkg = mTargetPkgCache.get(userId);
            Integer cachedUid = mTargetUidCache.get(userId);
            if (pkg.equals(cachedPkg) && cachedUid != null && cachedUid > 0) {
                return cachedUid;
            }
        }
        Context ctx = ensureContext(null);
        if (ctx == null) {
            return -1;
        }
        int uid = -1;
        final long token = Binder.clearCallingIdentity();
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm != null) {
                Object res = callMethod(pm, "getPackageUidAsUser", pkg, userId);
                if (res instanceof Integer) {
                    uid = (Integer) res;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (uid > 0) {
            synchronized (mStateLock) {
                mTargetPkgCache.put(userId, pkg);
                mTargetUidCache.put(userId, uid);
            }
        }
        return uid;
    }

    private TargetImeEntry getActiveTargetIme(int userId) {
        if (Boolean.TRUE.equals(mInOsenseHook.get())) {
            return null;
        }
        mInOsenseHook.set(Boolean.TRUE);
        try {
            Config cfg = getConfig(null, userId);
            if (cfg == null || !cfg.enabled) {
                return null;
            }
            String pkg = extractPkg(cfg.imeId);
            if (pkg == null) {
                return null;
            }
            int uid = resolveTargetUid(pkg, userId);
            return new TargetImeEntry(pkg, uid);
        } finally {
            mInOsenseHook.set(Boolean.FALSE);
        }
    }

    private String getConfiguredTargetPkgForUid(int uid) {
        if (uid <= 0) {
            return null;
        }
        // UserHandle.PER_USER_RANGE == 100000
        int userId = uid / 100000;
        TargetImeEntry entry = getActiveTargetIme(userId);
        if (entry != null && entry.uid == uid) {
            return entry.pkg;
        }
        return null;
    }

    private void unfreezeTargetIme(String imeId, int userId) {
        try {
            String pkg = extractPkg(imeId);
            if (pkg == null) {
                return;
            }
            int uid = resolveTargetUid(pkg, userId);
            if (uid <= 0) {
                return;
            }
            Object hans = mHansManager;
            if (hans == null && !mHansLookupDone) {
                mHansLookupDone = true;
                Class<?> hansCls = findClassIfExists(
                        "com.android.server.am.OplusHansManager",
                        Thread.currentThread().getContextClassLoader());
                if (hansCls == null && mSettingsRepoClass != null) {
                    hansCls = findClassIfExists(
                            "com.android.server.am.OplusHansManager",
                            mSettingsRepoClass.getClassLoader());
                }
                if (hansCls == null && mSystemClassLoader != null) {
                    hansCls = findClassIfExists(
                            "com.android.server.am.OplusHansManager",
                            mSystemClassLoader);
                }
                if (hansCls != null) {
                    hans = callStaticMethod(hansCls, "getInstance");
                    mHansManager = hans;
                }
            }
            if (hans != null) {
                callMethod(hans, "hansUnFreeze", uid, pkg, TAG);
            }
        } catch (Throwable t) {
            logError("unfreezeTargetIme error", t);
        }
    }

    /**
     * 读取配置。Settings provider 与本模块同在 system_server，读取是进程内调用；
     * getStringForUser/getIntForUser 为 @hide API，经反射调用。
     */
    private Config getConfig(Object imms, int userId) {
        final long now = SystemClock.elapsedRealtime();
        synchronized (mStateLock) {
            Config cached = mConfigCache.get(userId);
            if (cached != null && now - cached.loadedAt < CONFIG_TTL_MS) {
                return cached;
            }
        }
        Config cfg;
        try {
            ContentResolver resolver = mResolver;
            if (resolver == null) {
                Context ctx = ensureContext(imms);
                if (ctx == null) {
                    return null;
                }
                resolver = ctx.getContentResolver();
                mResolver = resolver;
            }
            String id = (String) callStaticMethod(Settings.Secure.class,
                    "getStringForUser", resolver, KEY_IME_ID, userId);
            Integer enabled = (Integer) callStaticMethod(Settings.Secure.class,
                    "getIntForUser", resolver, KEY_ENABLED, 1, userId);
            Integer termEnabled = (Integer) callStaticMethod(Settings.Secure.class,
                    "getIntForUser", resolver, KEY_TERMINAL_ENABLED, 1, userId);
            cfg = new Config(
                    enabled == null || enabled == 1,
                    termEnabled == null || termEnabled == 1,
                    id == null ? "" : id,
                    now);
        } catch (Throwable t) {
            if (imms != null) {
                logError("read config failed", t);
            }
            cfg = new Config(false, false, "", now);
        }
        synchronized (mStateLock) {
            mConfigCache.put(userId, cfg);
        }
        return cfg;
    }

    /** inputType 是否为密码变体（文本/数字/网页密码） */
    static boolean isPasswordType(int inputType) {
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        switch (inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_TEXT:
                return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            case InputType.TYPE_CLASS_NUMBER:
                return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
            default:
                return false;
        }
    }

    /**
     * 终端视图特征：
     * - Termux / MT 终端等：TYPE_NULL (class=0) 且带 IME_FLAG_NO_FULLSCREEN / IME_FLAG_NO_EXTRACT_UI
     * - DroidSpaces 等：仅设 TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS
     *   而未带 TYPE_CLASS_TEXT（0x80090，class 仍为 0）
     */
    static boolean isTerminalType(EditorInfo info) {
        if ((info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL) {
            return false;
        }
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
            return true;
        }
        return (info.imeOptions & (EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI)) != 0;
    }

    private boolean isImeEnabled(Object settings, String imeId) {
        List<?> enabled = (List<?>) callMethod(settings, "getEnabledInputMethodList");
        for (Object info : enabled) {
            if (imeId.equals(callMethod(info, "getId"))) {
                return true;
            }
        }
        return false;
    }

    // ---- 日志辅助（XposedInterface.log） ----

    private void logInfo(String msg) {
        log(Log.INFO, null, TAG + ": " + msg);
    }

    private void logWarn(String msg) {
        log(Log.WARN, null, TAG + ": " + msg);
    }

    private void logError(String msg, Throwable t) {
        log(Log.ERROR, null, TAG + ": " + msg, t);
    }

    // ---- 纯 Java 反射辅助（替代 legacy XposedHelpers） ----

    private static Class<?> findClass(String name, ClassLoader cl) throws ClassNotFoundException {
        return cl != null ? Class.forName(name, false, cl) : Class.forName(name);
    }

    private static Class<?> findClassIfExists(String name, ClassLoader cl) {
        try {
            return findClass(name, cl);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        String key = clazz.getName() + "#" + name + "@" + System.identityHashCode(clazz);
        Field cached = mFieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                mFieldCache.putIfAbsent(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "#" + name);
    }

    private Object getObjectField(Object obj, String name) {
        try {
            return findField(obj.getClass(), name).get(obj);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setObjectField(Object obj, String name, Object value) {
        try {
            findField(obj.getClass(), name).set(obj, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Object getStaticObjectField(Class<?> clazz, String name) {
        try {
            return findField(clazz, name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Method findMethodExact(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "#" + name);
    }

    private Object callMethod(Object obj, String name, Object... args) {
        return invokeBestMethod(obj.getClass(), obj, name, false, args);
    }

    private Object callStaticMethod(Class<?> clazz, String name, Object... args) {
        return invokeBestMethod(clazz, null, name, true, args);
    }

    private Object invokeBestMethod(
            Class<?> clazz, Object receiver, String name, boolean isStatic, Object[] args) {
        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i] != null ? args[i].getClass() : null;
        }
        MethodKey key = new MethodKey(clazz, name, isStatic, argTypes);
        Method target = mMethodCache.get(key);
        if (target == null) {
            target = resolveBestMethod(clazz, name, isStatic, argTypes);
            mMethodCache.putIfAbsent(key, target);
        }
        try {
            return target.invoke(receiver, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause != null ? cause : ite);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Method resolveBestMethod(
            Class<?> clazz, String name, boolean isStatic, Class<?>[] argTypes) {
        Method best = null;
        int bestScore = -1;
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                if (isStatic != Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] pTypes = m.getParameterTypes();
                if (pTypes.length != argTypes.length) {
                    continue;
                }
                int score = scoreMatch(pTypes, argTypes);
                if (score > bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
            c = c.getSuperclass();
        }
        if (best == null) {
            throw new RuntimeException(new NoSuchMethodException(clazz.getName() + "#" + name));
        }
        best.setAccessible(true);
        return best;
    }

    private static int scoreMatch(Class<?>[] pTypes, Class<?>[] argTypes) {
        int score = 0;
        for (int i = 0; i < pTypes.length; i++) {
            Class<?> p = pTypes[i];
            Class<?> a = argTypes[i];
            if (a == null) {
                if (p.isPrimitive()) {
                    return -1;
                }
                score += 1;
            } else if (p == a) {
                score += 4;
            } else if (p.isPrimitive() && wrapPrimitive(p) == a) {
                score += 3;
            } else if (p.isAssignableFrom(a)) {
                score += 2;
            } else if (p.isPrimitive() && isWideningMatch(unwrapWrapper(a), p)) {
                score += 1;
            } else {
                return -1;
            }
        }
        return score;
    }

    private static Class<?> wrapPrimitive(Class<?> p) {
        if (p == int.class) return Integer.class;
        if (p == boolean.class) return Boolean.class;
        if (p == long.class) return Long.class;
        if (p == float.class) return Float.class;
        if (p == double.class) return Double.class;
        if (p == byte.class) return Byte.class;
        if (p == short.class) return Short.class;
        if (p == char.class) return Character.class;
        return p;
    }

    private static Class<?> unwrapWrapper(Class<?> w) {
        if (w == Integer.class) return int.class;
        if (w == Boolean.class) return boolean.class;
        if (w == Long.class) return long.class;
        if (w == Float.class) return float.class;
        if (w == Double.class) return double.class;
        if (w == Byte.class) return byte.class;
        if (w == Short.class) return short.class;
        if (w == Character.class) return char.class;
        return w;
    }

    private static boolean isWideningMatch(Class<?> from, Class<?> to) {
        if (from == to) return true;
        if (from == byte.class) {
            return to == short.class || to == int.class || to == long.class
                    || to == float.class || to == double.class;
        }
        if (from == short.class || from == char.class) {
            return to == int.class || to == long.class || to == float.class || to == double.class;
        }
        if (from == int.class) {
            return to == long.class || to == float.class || to == double.class;
        }
        if (from == long.class) {
            return to == float.class || to == double.class;
        }
        if (from == float.class) {
            return to == double.class;
        }
        return false;
    }

    /** inputType 是否为密码变体（保留静态方法名以便测试/引用） */
    // 已在上方定义 isPasswordType / isTerminalType
}
