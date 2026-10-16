package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 音量条按钮列里的「分应用音量」入口按钮，位于静音 / 勿扰（以及降噪）按钮的下面。
 *
 * 设计要点：
 * 1. 只有真的有应用在播放媒体时才出现，没有满足条件时不占用按钮列的空间；
 * 2. 与降噪按钮一样直接插进原生 miui_ringer_btn_layout 里，紧跟在勿扰按钮之后
 *    （降噪行可见时排在降噪行之后），并加入原生 show/hide 动画的行序列，
 *    缩放、位移、触底拉伸都和原生按钮保持同一个整体；
 * 3. 与降噪按钮一样复用原生 miui_ringer_mode_layout + RingerButtonHelper，
 *    外观、毛玻璃、按压反馈都是原生的；
 * 4. 展开二级菜单时隐藏（入口是折叠态快捷入口）。
 */
final class MediaVolumeEntry {
    private static final String TAG = "HyperVolumeANC";
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static final String MISOUND_PACKAGE = "com.miui.misound";

    /** 自己注入的入口按钮 tag。 */
    private static final String ENTRY_TAG = "hypervolumeanc:app-volume-entry";
    /** 上游 AppVolumeBarHook 注入按钮的 tag：它还在时不再叠加第二个入口。 */
    private static final String UPSTREAM_ENTRY_TAG = "tag_app_volume_entry_root";

    static final String ACTION_EXPAND_MEDIA_VOLUME =
            "io.github.zhhhyyyyyy.hypervolumeanc.action.EXPAND_MEDIA_VOLUME";

    /** onFinishInflate 时视图还没挂到父节点上，等父节点就绪再注入；最多等这么多轮。 */
    private static final int MAX_INJECT_ATTEMPTS = 8;

    private static WeakReference<ViewGroup> entryRow = new WeakReference<>(null);
    private static WeakReference<View> entryDivider = new WeakReference<>(null);
    private static WeakReference<View> nativeDividerRef = new WeakReference<>(null);
    private static WeakReference<Object> entryHelper = new WeakReference<>(null);
    private static WeakReference<ViewGroup> dialogView = new WeakReference<>(null);
    private static WeakReference<View> dndRow = new WeakReference<>(null);
    private static WeakReference<Object> panelController = new WeakReference<>(null);
    /** 最近一次初始化的 show/hide 动画器，入口注入晚于动画器初始化时用它补加入序列。 */
    private static WeakReference<Object> showHideAnimator = new WeakReference<>(null);
    private static WeakReference<View> showHideAnimatorView = new WeakReference<>(null);

    /** 入口已经加入原生 show/hide 动画序列：之后缩放与位移都由原生动画驱动。 */
    private static volatile boolean animatorJoined;
    private static volatile boolean entryShown;
    private static volatile boolean panelExpanded;
    private static volatile boolean panelVisible;
    private static volatile float slideRingerY;
    private static volatile float slideDndY;
    /** 自愈式刷新节流：动画每帧只允许按固定间隔重算一次可见性。 */
    private static final long TICK_INTERVAL_MS = 250L;
    private static volatile long lastTickAt;

    private MediaVolumeEntry() {
    }

    // ---------------------------------------------------------------- 注入

    /** 原生音量面板 inflate 完成后调用，把入口按钮插到勿扰按钮下面。 */
    static void inject(View ringerLayout) {
        if (!(ringerLayout instanceof ViewGroup host)) {
            return;
        }
        // MiuiRingerModeLayout#onFinishInflate 触发时它还没有父节点（LayoutInflater 之后才
        // 把它加进音量对话框），这里等父节点出现再注入。
        injectWhenAttached(host, 0);
    }

    private static void injectWhenAttached(ViewGroup host, int attempt) {
        if (attempt > MAX_INJECT_ATTEMPTS) {
            HookLog.w("volume dialog view never appeared for the media volume entry");
            return;
        }
        host.post(() -> {
            if (host.getParent() instanceof ViewGroup dialog) {
                injectInto(host, dialog);
            } else {
                injectWhenAttached(host, attempt + 1);
            }
        });
    }

    private static void injectInto(ViewGroup host, ViewGroup dialog) {
        View divider = null;
        ViewGroup row = null;
        ViewGroup buttonLayout = null;
        try {
            if (dialog.findViewWithTag(ENTRY_TAG) != null) {
                return;
            }
            if (dialog.findViewWithTag(UPSTREAM_ENTRY_TAG) != null) {
                HookLog.i("AppVolumeBarHook entry detected; keeping the upstream button only");
                return;
            }
            // 入口是给按音量键弹出的普通音量面板用的；控制中心里的音量面板保持原生外观。
            Object needShowDialog = readField(host, "mNeedShowDialog");
            if (needShowDialog instanceof Boolean showDialog && !showDialog) {
                HookLog.i("control center volume panel detected; skipping the app volume entry");
                return;
            }

            Context context = dialog.getContext();
            int layoutId = resource(context, "layout", "miui_ringer_mode_layout");
            if (layoutId == 0) {
                HookLog.e("miui_ringer_mode_layout is unavailable");
                return;
            }

            View buttons = find(host, "miui_ringer_btn_layout");
            View state = find(host, "miui_ringer_state_layout");
            View dnd = find(host, "dnd_layout");
            View nativeDivider = find(host, "miui_volume_ringer_divider");
            if (!(buttons instanceof ViewGroup group) || dnd == null || nativeDivider == null) {
                HookLog.e("native ringer hierarchy is incomplete; entry not injected");
                return;
            }
            buttonLayout = group;

            row = (ViewGroup) LayoutInflater.from(context).inflate(layoutId, buttonLayout, false);
            row.setTag(ENTRY_TAG);
            row.setId(View.generateViewId());
            View blur = required(row, "bg_blur");
            View standard = required(row, "miui_standard_btn");
            View iconView = required(row, "icon");
            if (blur == null || standard == null || !(iconView instanceof ImageView icon)) {
                HookLog.e("official ringer template is incomplete");
                return;
            }
            View timer = find(row, "timer_layout");
            if (timer != null) {
                timer.setVisibility(View.GONE);
            }

            // 原生按钮的毛玻璃底、材质、按压动画都由 RingerButtonHelper 负责，
            // 与静音 / 勿扰 / 降噪按钮使用同一套实例样式。
            Object helper = createRingerHelper(host, row, false, false);
            entryHelper = new WeakReference<>(helper);
            applyEntryStyle(helper, icon);
            blur.setContentDescription("分应用音量，点击打开应用独立音量面板");
            blur.setOnClickListener(MediaVolumeEntry::onEntryClick);
            blur.setClickable(true);

            divider = new View(context);
            divider.setId(View.generateViewId());
            divider.setLayoutParams(copyLayoutParams(nativeDivider.getLayoutParams()));
            row.setVisibility(View.GONE);
            divider.setVisibility(View.GONE);

            // 紧跟在勿扰按钮后面；降噪行已经注入时排在降噪行后面，保持「静音 / 勿扰 / 降噪 / 分应用音量」的顺序。
            View anchor = dnd;
            View anc = VolumeButtonInjector.extraRow();
            if (anc != null && anc.getParent() == buttonLayout) {
                anchor = anc;
            }
            int insertAt = buttonLayout.indexOfChild(anchor) + 1;
            buttonLayout.addView(divider, insertAt);
            buttonLayout.addView(row, insertAt + 1);
            // 多了一行，按钮列所在的容器不能再被固定高度截断（降噪行同样这么处理）。
            if (state != null) {
                VolumeButtonInjector.setWrapContentHeight(state);
            }
            VolumeButtonInjector.setWrapContentHeight(host);

            entryRow = new WeakReference<>(row);
            entryDivider = new WeakReference<>(divider);
            nativeDividerRef = new WeakReference<>(nativeDivider);
            dialogView = new WeakReference<>(dialog);
            dndRow = new WeakReference<>(dnd);
            animatorJoined = false;
            tryJoinAnimator(dialog);
            MediaPlaybackWatcher.listen(context, MediaVolumeEntry::refreshVisibility);
            HookLog.i("media volume entry injected below the DND button host="
                    + host.getClass().getSimpleName() + " dialog=" + dialog.getClass().getSimpleName()
                    + " index=" + (insertAt + 1) + " animatorJoined=" + animatorJoined
                    + " standardBackground=" + (standard.getBackground() != null));
            HookDiagnostics.ok("sysui_entry", "分应用音量入口行",
                    "已注入勿扰按钮下方 · 背景=" + (standard.getBackground() != null)
                            + " · 并入原生动画=" + animatorJoined);
            HookDiagnostics.hit("sysui_entry_state", "入口显示判定", "等待判定");
        } catch (Throwable error) {
            if (buttonLayout != null) {
                if (row != null && row.getParent() == buttonLayout) {
                    buttonLayout.removeView(row);
                }
                if (divider != null && divider.getParent() == buttonLayout) {
                    buttonLayout.removeView(divider);
                }
            }
            HookLog.e("failed to inject the media volume entry", error);
            HookDiagnostics.failed("sysui_entry", "分应用音量入口行", error);
        }
    }

    // ---------------------------------------------------------------- 原生 show/hide 动画

    /** 原生 show/hide 动画器初始化时调用：记下它，并在入口已经存在时立刻并入行序列。 */
    static void onShowHideAnimatorReady(Object animator, View volumeView) {
        showHideAnimator = new WeakReference<>(animator);
        showHideAnimatorView = new WeakReference<>(volumeView);
        animatorJoined = false;
        ViewGroup dialog = dialogView.get();
        if (dialog != null) {
            tryJoinAnimator(dialog);
        }
    }

    private static void tryJoinAnimator(ViewGroup dialog) {
        ViewGroup row = entryRow.get();
        Object animator = showHideAnimator.get();
        View volumeView = showHideAnimatorView.get();
        if (row == null || animator == null || volumeView == null || animatorJoined) {
            return;
        }
        // 只并入属于同一个音量面板的动画器。
        if (!isInside(row, volumeView)) {
            return;
        }
        if (VolumeButtonInjector.appendRowToShowHideAnimator(animator, volumeView, row)) {
            animatorJoined = true;
            HookLog.i("media volume entry joined native show/hide animation sequence");
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** 音量条即将展示：在原生套用折叠态布局之前决定入口是否出现。 */
    static void prepareShow(View dialog) {
        ViewGroup group = dialog instanceof ViewGroup candidate ? candidate : dialogView.get();
        if (group == null) {
            return;
        }
        if (dialogView.get() != group) {
            // 展示的是另一个音量面板（控制中心面板或重建后的面板），把入口搬过去。
            adoptPanel(group);
        }
        Object needShowDialog = readField(group, "mNeedShowDialog");
        if (needShowDialog instanceof Boolean need && !need) {
            // 控制中心里的音量面板保持原生外观：不显示入口。
            panelVisible = false;
            panelExpanded = true;
            setEntryShown(group, false);
            return;
        }
        panelVisible = true;
        panelExpanded = isExpanded(group);
        applyVisibility(group);
    }

    /** 在给定音量面板里找到原生 ringer 布局并按需注入入口（幂等）。 */
    private static void adoptPanel(ViewGroup dialog) {
        View host = find(dialog, "miui_volume_ringer_layout");
        if (host instanceof ViewGroup ringer) {
            injectInto(ringer, dialog);
        }
    }

    /** 二级菜单展开 / 收起后同步入口。 */
    static void onExpandedChanged(View dialog, boolean expanded) {
        if (!(dialog instanceof ViewGroup group)) {
            return;
        }
        panelExpanded = expanded;
        applyVisibility(group);
    }

    /**
     * 音量条开始收起：带动画时不立刻复位，入口要跟着面板一起滑出屏外，
     * 收起动画结束后再复位。
     */
    static void onDismissStarted(View dialog, boolean withAnimation) {
        if (!withAnimation) {
            onHideComplete();
        }
    }

    /** 收起动画结束：复位入口，下一次展示重新判断条件。 */
    static void onHideComplete() {
        ViewGroup group = dialogView.get();
        if (group != null) {
            setEntryShown(group, false);
        }
        panelVisible = false;
        resetAnimation();
    }

    /** 设置里的开关变化后重新判断。 */
    static void refreshVisibility() {
        ViewGroup group = dialogView.get();
        // 面板没显示时不做改动：这里只影响可见状态，避免把收起状态的布局算乱。
        if (group != null && panelVisible) {
            applyVisibility(group);
        }
    }

    /**
     * 自愈式刷新：官方动画每帧都会回调这里，只要面板在动就按固定间隔重算一次可见性。
     * 这样即使某个生命周期钩子在当前 ROM 上没命中，入口依然会在下一次音量面板动画时出现。
     */
    static void tick() {
        ViewGroup group = dialogView.get();
        if (group == null || !group.isAttachedToWindow()) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTickAt < TICK_INTERVAL_MS) {
            return;
        }
        lastTickAt = now;
        applyVisibility(group);
    }

    /**
     * 触底 / 拖拽拉伸时官方按等差序列驱动静音与勿扰行：静音是第 0 项、勿扰是第 1 项，
     * 降噪行是第 2 项，入口排在最后——降噪行可见时是第 3 项，否则是第 2 项。
     */
    static void onSlideRinger(float ringerY) {
        slideRingerY = ringerY;
        applySlideTranslation();
    }

    static void onSlideDnd(float dndY) {
        slideDndY = dndY;
        applySlideTranslation();
    }

    private static void applySlideTranslation() {
        ViewGroup entry = entryRow.get();
        if (entry != null && entry.getVisibility() == View.VISIBLE) {
            float step = slideDndY - slideRingerY;
            int position = VolumeButtonInjector.extraRowVisible() ? 3 : 2;
            entry.setTranslationY(slideRingerY + position * step);
        }
        VolumeButtonInjector.setExtraRowTranslationY(2f * slideDndY - slideRingerY);
    }

    static void onSlideReset() {
        slideRingerY = 0f;
        slideDndY = 0f;
        ViewGroup entry = entryRow.get();
        if (entry != null) {
            entry.setTranslationY(0f);
        }
        VolumeButtonInjector.setExtraRowTranslationY(0f);
    }

    static void setPanelController(Object controller) {
        panelController = new WeakReference<>(controller);
    }

    /**
     * 应用实例按钮的材质与毛玻璃底。
     *
     * 注意：RingerButtonHelper.updateState() 在「展开态与开关状态都没变」时会直接 return，
     * 而毛玻璃底（bg_blur 的模糊注册）与前景材质都只在那里面应用。复制出来的按钮初始
     * 状态与官方按钮一致，直接调用会被这个提前返回挡掉，按钮就没有背景。
     * 这里用一次状态来回切换强制它真正走一遍材质应用，最后停在「未激活」的外观上。
     */
    private static void applyEntryStyle(Object helper, ImageView icon) {
        invoke(helper, "setRingerMode", new Class<?>[]{boolean.class}, true);
        invoke(helper, "updateState", new Class<?>[0]);
        invoke(helper, "setRingerMode", new Class<?>[]{boolean.class}, false);
        invoke(helper, "updateState", new Class<?>[0]);
        // 折叠态尺寸与毛玻璃底；onExpanded 不会覆盖前景材质。
        invoke(helper, "onExpanded",
                new Class<?>[]{boolean.class, boolean.class}, false, true);
        if (icon != null) {
            icon.setImageDrawable(new EqualizerDrawable());
            icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            icon.setVisibility(View.VISIBLE);
        }
    }

    /** 材质模式变化 / 资源刷新时重新套一遍样式（updateState 会连带把图标换成官方图标，这里再换回来）。 */
    static void refreshStyle() {
        ViewGroup row = entryRow.get();
        Object helper = entryHelper.get();
        if (row == null || helper == null) {
            return;
        }
        View iconView = find(row, "icon");
        applyEntryStyle(helper, iconView instanceof ImageView icon ? icon : null);
        View standard = find(row, "miui_standard_btn");
        HookLog.i("media volume entry style refreshed standardBackground="
                + (standard != null && standard.getBackground() != null));
    }

    // ---------------------------------------------------------------- 动画

    /**
     * 入口已经并入原生 show/hide 动画序列时，缩放与位移全部由原生动画驱动，这里什么都不做；
     * 没并进去（动画器没找到 / 字段不匹配）时，退回到跟着勿扰行同步缩放与透明度。
     */
    static void syncAnimation() {
        if (animatorJoined) {
            return;
        }
        ViewGroup entry = entryRow.get();
        if (entry == null || entry.getVisibility() != View.VISIBLE) {
            return;
        }
        View dnd = dndRow.get();
        if (dnd == null) {
            return;
        }
        entry.setScaleX(dnd.getScaleX());
        entry.setScaleY(dnd.getScaleY());
        entry.setAlpha(dnd.getAlpha());
    }

    private static void resetAnimation() {
        ViewGroup entry = entryRow.get();
        if (entry == null) {
            return;
        }
        entry.setScaleX(1f);
        entry.setScaleY(1f);
        entry.setAlpha(1f);
        entry.setTranslationY(0f);
    }

    // ---------------------------------------------------------------- 内部实现

    private static void applyVisibility(ViewGroup dialog) {
        boolean enabled = HyperVolumeAncSettings.appVolumeEntryEnabled();
        boolean expanded = panelExpanded;
        boolean playing = MediaPlaybackWatcher.hasActiveMediaPlayback(dialog.getContext());
        boolean visible = enabled && !expanded && playing;
        HookLog.i("entry visibility option=" + enabled + " expanded=" + expanded
                + " playing=" + playing + " -> " + (visible ? "VISIBLE" : "GONE"));
        HookDiagnostics.hit("sysui_entry_state", "入口显示判定",
                "开关=" + (enabled ? "开" : "关")
                        + " · 二级菜单=" + (expanded ? "展开" : "收起")
                        + " · 有应用在播放=" + (playing ? "是" : "否")
                        + " → " + (visible ? "显示" : "隐藏"));
        setEntryShown(dialog, visible);
    }

    private static void setEntryShown(ViewGroup dialog, boolean visible) {
        ViewGroup entry = entryRow.get();
        if (entry == null || dialogView.get() != dialog) {
            return;
        }
        boolean changed = entryShown != visible;
        entryShown = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (visible) {
            syncDividerSize();
        }
        if (entry.getVisibility() != visibility) {
            entry.setVisibility(visibility);
        }
        View divider = entryDivider.get();
        if (divider != null && divider.getVisibility() != visibility) {
            divider.setVisibility(visibility);
        }
        if (changed) {
            HookLog.i("entry " + (visible ? "shown" : "hidden")
                    + " visibility=" + entry.getVisibility());
        }
        if (visible) {
            syncAnimation();
        } else {
            resetAnimation();
        }
    }

    /** 入口与上一行之间的间距跟随原生分隔条（折叠态与展开态的间距不同）。 */
    private static void syncDividerSize() {
        View divider = entryDivider.get();
        View source = nativeDividerRef.get();
        if (divider == null || source == null) {
            return;
        }
        ViewGroup.LayoutParams from = source.getLayoutParams();
        ViewGroup.LayoutParams to = divider.getLayoutParams();
        if (from != null && to != null
                && (to.width != from.width || to.height != from.height)) {
            to.width = from.width;
            to.height = from.height;
            divider.setLayoutParams(to);
        }
    }

    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams source) {
        if (source instanceof LinearLayout.LayoutParams linear) {
            return new LinearLayout.LayoutParams(linear);
        }
        return new LinearLayout.LayoutParams(source);
    }

    private static boolean isInside(View view, View ancestor) {
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View current) {
            if (current == ancestor) {
                return true;
            }
            parent = current.getParent();
        }
        return view == ancestor;
    }

    private static boolean isExpanded(View dialog) {
        try {
            Method method = dialog.getClass().getMethod("isExpanded");
            Object value = method.invoke(dialog);
            if (value instanceof Boolean expanded) {
                return expanded;
            }
        } catch (Throwable ignored) {
            // 部分版本的内置音量视图没有 isExpanded()，退回记录值。
        }
        return panelExpanded;
    }

    private static void onEntryClick(View view) {
        Context context = view.getContext();
        HookLog.i("media volume entry clicked");
        HookDiagnostics.hit("sysui_entry_click", "入口点击", "已请求小米声音展开分应用音量面板");
        try {
            Intent intent = new Intent(ACTION_EXPAND_MEDIA_VOLUME)
                    .setPackage(MISOUND_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            HookLog.e("failed to ask MiSound to expand the app volume panel", error);
        }
        dismissVolumeDialog();
    }

    /** 先收起 SystemUI 自己的音量条，随后由小米声音弹出分应用音量面板。 */
    private static void dismissVolumeDialog() {
        Object controller = panelController.get();
        if (controller != null) {
            try {
                Method dismiss = controller.getClass().getDeclaredMethod("dismissH", int.class);
                dismiss.setAccessible(true);
                dismiss.invoke(controller, 8);
                return;
            } catch (Throwable error) {
                HookLog.w("failed to dismiss the volume dialog through its controller", error);
            }
        }
        View dialog = dialogView.get();
        if (dialog != null) {
            try {
                Method dismiss = dialog.getClass().getDeclaredMethod(
                        "dismissH", boolean.class, Runnable.class);
                dismiss.setAccessible(true);
                dismiss.invoke(dialog, true, null);
                return;
            } catch (Throwable error) {
                HookLog.w("failed to dismiss the volume dialog view", error);
            }
        }
    }

    private static Object createRingerHelper(
            ViewGroup host, ViewGroup row, boolean isZen, boolean state) throws Exception {
        Class<?> helperClass = null;
        for (Class<?> candidate : host.getClass().getDeclaredClasses()) {
            if ("RingerButtonHelper".equals(candidate.getSimpleName())) {
                helperClass = candidate;
                break;
            }
        }
        if (helperClass == null) {
            throw new ClassNotFoundException(host.getClass().getName() + "$RingerButtonHelper");
        }
        Constructor<?> constructor = helperClass.getDeclaredConstructor(
                host.getClass(), View.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(host, row, isZen, state);
    }

    private static void invoke(Object target, String name, Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(target, arguments);
        } catch (Throwable error) {
            HookLog.e("native ringer helper call failed: " + name, error);
        }
    }

    private static View find(View root, String name) {
        if (root == null) {
            return null;
        }
        int id = resource(root.getContext(), "id", name);
        return id == 0 ? null : root.findViewById(id);
    }

    private static View required(View root, String name) {
        return find(root, name);
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                // 继续在父类里找。
            }
        }
        return null;
    }

    private static int resource(Context context, String type, String name) {
        Resources resources = context.getResources();
        int id = resources.getIdentifier(name, type, PLUGIN_PACKAGE);
        if (id == 0) {
            id = resources.getIdentifier(name, type, context.getPackageName());
        }
        return id;
    }

    /**
     * 分应用音量入口图标：三轨均衡器，与官方「应用独立音量」图标一致。
     * 纯代码绘制，不依赖插件资源，保证任何版本都能显示。
     */
    private static final class EqualizerDrawable extends Drawable {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ColorStateList tintList;

        EqualizerDrawable() {
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setColor(Color.WHITE);
            knobPaint.setStyle(Paint.Style.FILL);
            knobPaint.setColor(Color.WHITE);
        }

        @Override
        public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float width = bounds.width();
            float height = bounds.height();
            if (width <= 0f || height <= 0f) {
                return;
            }
            int color = tintList != null
                    ? tintList.getColorForState(getState(), Color.WHITE)
                    : Color.WHITE;
            linePaint.setColor(color);
            knobPaint.setColor(color);

            float scaleX = width / 24f;
            float scaleY = height / 24f;
            linePaint.setStrokeWidth(2f * scaleX);

            drawTrack(canvas, bounds, scaleX, scaleY, 5.5f, 13f);
            drawTrack(canvas, bounds, scaleX, scaleY, 12f, 8f);
            drawTrack(canvas, bounds, scaleX, scaleY, 18.5f, 15f);
        }

        private void drawTrack(Canvas canvas, RectF bounds, float scaleX, float scaleY,
                float trackX, float knobY) {
            float centerX = bounds.left + trackX * scaleX;
            canvas.drawLine(centerX, bounds.top + 4.5f * scaleY,
                    centerX, bounds.top + 19.5f * scaleY, linePaint);
            float knobWidth = 4.2f * scaleX;
            float knobHeight = 5.6f * scaleY;
            float centerY = bounds.top + knobY * scaleY;
            RectF knob = new RectF(centerX - knobWidth / 2f, centerY - knobHeight / 2f,
                    centerX + knobWidth / 2f, centerY + knobHeight / 2f);
            float radius = 1.8f * scaleX;
            canvas.drawRoundRect(knob, radius, radius, knobPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            linePaint.setAlpha(alpha);
            knobPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            linePaint.setColorFilter(colorFilter);
            knobPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public void setTintList(ColorStateList tint) {
            tintList = tint;
            invalidateSelf();
        }

        @Override
        public boolean isStateful() {
            return tintList != null && tintList.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
