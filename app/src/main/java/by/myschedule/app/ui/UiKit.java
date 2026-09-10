package by.myschedule.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

public final class UiKit {
    private static final String APPEARANCE_PREFERENCES = "appearance_preferences";
    private static final String KEY_DARK_THEME = "dark_theme";

    public static final int ON_ACCENT = Color.WHITE;
    public static int IVORY;
    public static int SURFACE;
    public static int DUSTY_BLUE;
    public static int BLUE_END;
    public static int INK;
    public static int GRAY;
    public static int LINE;
    public static int SOFT_GRAY;
    public static int MID_GRAY;
    public static int PALE_BLUE;
    public static int LESSON_SURFACE;
    public static int LESSON_BORDER;
    public static int PALE_RED;
    public static int PALE_ORANGE;
    public static int PALE_GREEN;
    public static int GREEN;
    public static int ORANGE;
    public static int RED;
    private static boolean darkTheme;

    static {
        applyTheme(false);
    }

    private UiKit() {
    }

    public static boolean loadDarkTheme(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(APPEARANCE_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_DARK_THEME, false);
    }

    public static void saveDarkTheme(Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(APPEARANCE_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DARK_THEME, enabled)
                .apply();
    }

    public static void applyTheme(boolean dark) {
        darkTheme = dark;
        if (dark) {
            IVORY = Color.rgb(14, 17, 22);
            SURFACE = Color.rgb(24, 29, 37);
            DUSTY_BLUE = Color.rgb(91, 155, 255);
            BLUE_END = Color.rgb(41, 196, 230);
            INK = Color.rgb(243, 246, 250);
            GRAY = Color.rgb(169, 178, 193);
            LINE = Color.rgb(48, 55, 68);
            SOFT_GRAY = Color.rgb(30, 35, 44);
            MID_GRAY = Color.rgb(36, 42, 52);
            LESSON_BORDER = Color.rgb(50, 58, 72);
            GREEN = Color.rgb(55, 207, 139);
            ORANGE = Color.rgb(255, 174, 66);
            RED = Color.rgb(255, 108, 116);
        } else {
            IVORY = Color.rgb(255, 255, 255);
            SURFACE = Color.WHITE;
            DUSTY_BLUE = Color.rgb(13, 99, 229);
            BLUE_END = Color.rgb(32, 195, 232);
            INK = Color.rgb(18, 22, 31);
            GRAY = Color.rgb(101, 109, 124);
            LINE = Color.rgb(232, 235, 240);
            SOFT_GRAY = Color.rgb(246, 247, 249);
            MID_GRAY = Color.rgb(239, 242, 245);
            LESSON_BORDER = Color.rgb(238, 240, 244);
            GREEN = Color.rgb(0, 168, 107);
            ORANGE = Color.rgb(230, 118, 0);
            RED = Color.rgb(215, 50, 56);
        }
        PALE_BLUE = SOFT_GRAY;
        LESSON_SURFACE = SURFACE;
        PALE_RED = SURFACE;
        PALE_ORANGE = SURFACE;
        PALE_GREEN = SURFACE;
    }

    public static boolean isDarkTheme() {
        return darkTheme;
    }

    public static void configureWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setStatusBarColor(IVORY);
        window.setNavigationBarColor(IVORY);
        int flags = darkTheme ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!darkTheme) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout vertical(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout horizontal(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static TextView text(Context context, String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0f, 1.08f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    public static TextView primaryButton(Context context, String label) {
        TextView button = text(context, label, 14, ON_ACCENT, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 50));
        button.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12));
        button.setBackground(gradientRipple(context, DUSTY_BLUE, BLUE_END, 25));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    public static TextView secondaryButton(Context context, String label) {
        TextView button = text(context, label, 14, INK, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 17), dp(context, 11), dp(context, 17), dp(context, 11));
        button.setBackground(ripple(context, SURFACE, 18, 1, LINE));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    public static TextView smallButton(Context context, String label, boolean selected) {
        int fill = selected ? DUSTY_BLUE : SURFACE;
        int textColor = selected ? ON_ACCENT : INK;
        TextView button = text(context, label, 12, textColor, selected);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 42));
        button.setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9));
        button.setBackground(selected
                ? gradientRipple(context, DUSTY_BLUE, BLUE_END, 21)
                : ripple(context, fill, 18, 1, LINE));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    public static LinearLayout card(Context context, int fillColor) {
        LinearLayout card = vertical(context);
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setBackground(ripple(context, fillColor, 22, 1, LESSON_BORDER));
        card.setElevation(dp(context, 5));
        return card;
    }

    public static EditText input(Context context, String hint, boolean multiline) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setHintTextColor(GRAY);
        input.setTextColor(INK);
        input.setTextSize(15);
        input.setPadding(dp(context, 16), dp(context, 13), dp(context, 16), dp(context, 13));
        input.setBackground(shape(SOFT_GRAY, dp(context, 16), dp(context, 1), LINE));
        input.setSingleLine(!multiline);
        if (multiline) {
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setMinLines(4);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        return input;
    }

    public static Space space(Context context, int heightDp) {
        Space space = new Space(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return space;
    }

    public static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(LINE);
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return line;
    }

    public static FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    public static void margins(View view, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ((ViewGroup.MarginLayoutParams) params).setMargins(dp(view.getContext(), left), dp(view.getContext(), top),
                dp(view.getContext(), right), dp(view.getContext(), bottom));
        view.setLayoutParams(params);
    }

    public static GradientDrawable shape(int fillColor, float radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(radiusDp);
        if (strokeDp > 0) background.setStroke(strokeDp, strokeColor);
        return background;
    }

    public static RippleDrawable ripple(Context context, int fillColor, float radiusDp,
                                        int strokeDp, int strokeColor) {
        GradientDrawable content = shape(fillColor, dp(context, radiusDp), dp(context, strokeDp), strokeColor);
        int rippleColor = darkTheme
                ? Color.argb(42, 255, 255, 255)
                : Color.argb(38, 11, 13, 20);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    public static RippleDrawable gradientRipple(Context context, int startColor, int endColor,
                                                float radiusDp) {
        GradientDrawable content = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{startColor, endColor});
        content.setCornerRadius(dp(context, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(42, 255, 255, 255)), content, null);
    }
}
