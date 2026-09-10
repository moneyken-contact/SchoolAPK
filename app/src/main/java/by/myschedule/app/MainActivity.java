package by.myschedule.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import by.myschedule.app.data.AppRepository;
import by.myschedule.app.email.BackgroundSyncScheduler;
import by.myschedule.app.email.EmailSyncGate;
import by.myschedule.app.email.ImapClient;
import by.myschedule.app.logic.BellSchedule;
import by.myschedule.app.logic.HolidayCalendar;
import by.myschedule.app.logic.LessonTimeline;
import by.myschedule.app.logic.RoomFormatter;
import by.myschedule.app.logic.SchedulePolicy;
import by.myschedule.app.logic.SubjectMatcher;
import by.myschedule.app.model.EmailSettings;
import by.myschedule.app.model.Homework;
import by.myschedule.app.model.HomeworkStatus;
import by.myschedule.app.model.LessonBlock;
import by.myschedule.app.model.ReceivedAnswer;
import by.myschedule.app.notifications.HomeworkReminderScheduler;
import by.myschedule.app.notifications.NotificationHelper;
import by.myschedule.app.security.SecurePasswordStore;
import by.myschedule.app.ui.UiKit;
import by.myschedule.app.widget.SchoolWidgetProvider;

public final class MainActivity extends Activity {
    public static final String EXTRA_OPEN_SCREEN = "open_screen";
    private static final String APP_VERSION = "0.8.1";
    private static final int REQUEST_NOTIFICATIONS = 701;
    private static final int REQUEST_CREATE_BACKUP = 702;
    private static final int REQUEST_OPEN_BACKUP = 703;
    private static final Locale RUSSIAN = new Locale("ru", "RU");
    private static final DateTimeFormatter DAY_NAME = DateTimeFormatter.ofPattern("EEEE", RUSSIAN);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("d MMMM", RUSSIAN);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", RUSSIAN);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", RUSSIAN)
            .withZone(ZoneId.systemDefault());

    private final EnumMap<Screen, NavigationItem> navigationButtons = new EnumMap<>(Screen.class);
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable scheduleTicker = new Runnable() {
        @Override
        public void run() {
            if (currentScreen != Screen.SCHEDULE || liveScheduleDate == null) return;
            if (!scheduleDateManuallySelected) {
                LocalDate policyDate = SchedulePolicy.scheduleDateFor(ZonedDateTime.now());
                if (!policyDate.equals(liveScheduleDate)) {
                    selectedScheduleDate = policyDate;
                    showScreen(Screen.SCHEDULE);
                    return;
                }
            }
            List<LessonBlock> lessons = HolidayCalendar.isHoliday(liveScheduleDate)
                    ? new ArrayList<>()
                    : repository.getLessonsForDay(liveScheduleDate.getDayOfWeek().getValue());
            LessonTimeline.State state = LessonTimeline.evaluate(
                    liveScheduleDate, LocalDate.now(), LocalTime.now(), lessons);
            if (!state.key().equals(liveTimelineKey)) {
                showScreen(Screen.SCHEDULE);
                return;
            }
            updateLiveStatus(state);
            mainHandler.postDelayed(this, 15_000L);
        }
    };

    private AppRepository repository;
    private SecurePasswordStore passwordStore;
    private FrameLayout content;
    private LinearLayout navigation;
    private Screen currentScreen = Screen.SCHEDULE;
    private LocalDate selectedScheduleDate;
    private boolean scheduleDateManuallySelected;
    private int editorDay = DayOfWeek.MONDAY.getValue();
    private boolean syncInProgress;
    private String lastSyncError = "";
    private long lastSyncAttemptAt;
    private TextView liveScheduleTitle;
    private TextView liveScheduleSubtitle;
    private LocalDate liveScheduleDate;
    private String liveTimelineKey = "";
    private boolean refreshScheduleOnResume;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean darkTheme = UiKit.loadDarkTheme(this);
        UiKit.applyTheme(darkTheme);
        setTheme(darkTheme ? R.style.Theme_SchoolSchedule_Dark : R.style.Theme_SchoolSchedule);
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);
        repository = new AppRepository(this);
        passwordStore = new SecurePasswordStore(this);
        NotificationHelper.ensureChannels(this);
        HomeworkReminderScheduler.scheduleNext(this);
        BackgroundSyncScheduler.update(this);
        buildShell();
        showScreen(screenFromIntent(getIntent()));
        getIntent().removeExtra(EXTRA_OPEN_SCREEN);
        SchoolWidgetProvider.updateAll(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Screen requestedScreen = screenFromIntent(intent);
        if (requestedScreen == Screen.SCHEDULE) {
            selectedScheduleDate = null;
            scheduleDateManuallySelected = false;
        }
        showScreen(requestedScreen);
        intent.removeExtra(EXTRA_OPEN_SCREEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (refreshScheduleOnResume && repository != null && currentScreen == Screen.SCHEDULE) {
            refreshScheduleOnResume = false;
            showScreen(Screen.SCHEDULE);
        }
    }

    @Override
    protected void onStop() {
        refreshScheduleOnResume = currentScreen == Screen.SCHEDULE;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(scheduleTicker);
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentScreen == Screen.ANSWERS) {
            showScreen(Screen.HOMEWORK);
        } else if (currentScreen == Screen.EDIT_SCHEDULE) {
            showScreen(Screen.SCHEDULE);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_CREATE_BACKUP) {
            writeBackup(uri);
        } else if (requestCode == REQUEST_OPEN_BACKUP) {
            readBackup(uri);
        }
    }

    private void buildShell() {
        LinearLayout root = UiKit.vertical(this);
        root.setBackgroundColor(UiKit.IVORY);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(UiKit.divider(this));
        navigation = UiKit.horizontal(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 5),
                UiKit.dp(this, 6), UiKit.dp(this, 5));
        navigation.setBackgroundColor(UiKit.SURFACE);
        navigation.setElevation(UiKit.dp(this, 10));

        addNavigationButton(Screen.SCHEDULE, "Расписание", R.drawable.ic_nav_calendar);
        addNavigationButton(Screen.HOMEWORK, "Домашка", R.drawable.ic_subject_book);
        addNavigationButton(Screen.ALL_HOMEWORK, "Все задания", R.drawable.ic_nav_list);
        addNavigationButton(Screen.SETTINGS, "Настройки", R.drawable.ic_nav_settings);
        root.addView(navigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 68)));

        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
        }
        setContentView(root);
    }

    private void addNavigationButton(Screen screen, String label, int iconResource) {
        LinearLayout button = UiKit.vertical(this);
        button.setGravity(Gravity.CENTER);
        button.setPadding(UiKit.dp(this, 3), UiKit.dp(this, 2), UiKit.dp(this, 3), UiKit.dp(this, 2));
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(UiKit.ripple(this, Color.TRANSPARENT, 14, 0, Color.TRANSPARENT));
        button.setOnClickListener(view -> {
            if (screen == Screen.SCHEDULE) {
                selectedScheduleDate = null;
                scheduleDateManuallySelected = false;
            }
            showScreen(screen);
        });

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setContentDescription(label);
        icon.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 4), UiKit.dp(this, 4), UiKit.dp(this, 4));
        button.addView(icon, new LinearLayout.LayoutParams(UiKit.dp(this, 30), UiKit.dp(this, 30)));
        TextView caption = UiKit.text(this, label, 9.5f, UiKit.GRAY, false);
        caption.setGravity(Gravity.CENTER);
        button.addView(caption, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        navigationButtons.put(screen, new NavigationItem(icon, caption));
        navigation.addView(button, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void showScreen(Screen screen) {
        mainHandler.removeCallbacks(scheduleTicker);
        liveScheduleTitle = null;
        liveScheduleSubtitle = null;
        liveScheduleDate = null;
        liveTimelineKey = "";
        currentScreen = screen;
        content.removeAllViews();
        navigation.setVisibility(screen == Screen.ANSWERS || screen == Screen.EDIT_SCHEDULE
                ? View.GONE : View.VISIBLE);

        View screenView;
        switch (screen) {
            case HOMEWORK:
                screenView = buildHomeworkScreen();
                break;
            case ALL_HOMEWORK:
                screenView = buildAllHomeworkScreen();
                break;
            case SETTINGS:
                screenView = buildSettingsScreen();
                break;
            case ANSWERS:
                screenView = buildAnswersScreen();
                break;
            case EDIT_SCHEDULE:
                screenView = buildScheduleEditor();
                break;
            case SCHEDULE:
            default:
                screenView = buildScheduleScreen();
                break;
        }
        content.addView(screenView, UiKit.matchFrame());
        updateNavigation();

        if (screen == Screen.ANSWERS) {
            content.post(this::maybeAutoSync);
        } else if (screen == Screen.SCHEDULE) {
            mainHandler.postDelayed(scheduleTicker, 15_000L);
        }
    }

    private void updateNavigation() {
        for (Map.Entry<Screen, NavigationItem> entry : navigationButtons.entrySet()) {
            boolean selected = entry.getKey() == currentScreen;
            NavigationItem item = entry.getValue();
            item.label.setTextColor(selected ? UiKit.DUSTY_BLUE : UiKit.GRAY);
            item.label.setTypeface(null, selected
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            item.icon.setColorFilter(selected ? UiKit.DUSTY_BLUE : UiKit.GRAY);
            item.icon.setBackground(UiKit.ripple(this, Color.TRANSPARENT,
                    15, 0, Color.TRANSPARENT));
        }
    }

    private View buildScheduleScreen() {
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate policyDate = SchedulePolicy.scheduleDateFor(now);
        if (selectedScheduleDate == null || !scheduleDateManuallySelected) {
            selectedScheduleDate = policyDate;
        }
        LocalDate targetDate = selectedScheduleDate;

        LinearLayout screen = UiKit.vertical(this);
        screen.setBackgroundColor(UiKit.IVORY);

        LinearLayout header = UiKit.vertical(this);
        header.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 10),
                UiKit.dp(this, 16), UiKit.dp(this, 12));

        LinearLayout titleBar = UiKit.horizontal(this);
        View titleSpacer = new View(this);
        titleBar.addView(titleSpacer, new LinearLayout.LayoutParams(
                UiKit.dp(this, 44), UiKit.dp(this, 44)));
        TextView screenTitle = UiKit.text(this, "Расписание", 26, UiKit.INK, true);
        screenTitle.setGravity(Gravity.CENTER);
        titleBar.addView(screenTitle, new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 44), 1f));
        ImageView editButton = new ImageView(this);
        editButton.setImageResource(R.drawable.ic_edit);
        editButton.setColorFilter(UiKit.INK);
        editButton.setContentDescription("Изменить уроки и кабинеты");
        editButton.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12),
                UiKit.dp(this, 12), UiKit.dp(this, 12));
        editButton.setBackground(UiKit.ripple(this, UiKit.SURFACE, 14, 1, UiKit.LINE));
        editButton.setClickable(true);
        editButton.setFocusable(true);
        editButton.setOnClickListener(view -> {
            editorDay = targetDate.getDayOfWeek().getValue();
            if (editorDay > DayOfWeek.SATURDAY.getValue()) {
                editorDay = policyDate.getDayOfWeek().getValue();
            }
            showScreen(Screen.EDIT_SCHEDULE);
        });
        titleBar.addView(editButton, new LinearLayout.LayoutParams(
                UiKit.dp(this, 44), UiKit.dp(this, 44)));
        header.addView(titleBar, UiKit.matchWrap());
        header.addView(UiKit.space(this, 16));

        HorizontalScrollView dateScroll = new HorizontalScrollView(this);
        dateScroll.setHorizontalScrollBarEnabled(false);
        dateScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout dateRow = UiKit.horizontal(this);

        LinearLayout calendarHolder = UiKit.vertical(this);
        calendarHolder.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ImageView calendar = new ImageView(this);
        calendar.setImageResource(R.drawable.ic_nav_calendar);
        calendar.setColorFilter(UiKit.INK);
        calendar.setContentDescription("Перейти к ближайшему учебному дню");
        calendar.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 17),
                UiKit.dp(this, 17), UiKit.dp(this, 17));
        calendar.setBackground(UiKit.ripple(this, UiKit.MID_GRAY, 31, 0, Color.TRANSPARENT));
        calendar.setFocusable(true);
        calendar.setOnClickListener(view -> {
            selectedScheduleDate = SchedulePolicy.scheduleDateFor(ZonedDateTime.now());
            scheduleDateManuallySelected = false;
            showScreen(Screen.SCHEDULE);
        });
        calendarHolder.addView(calendar, new LinearLayout.LayoutParams(
                UiKit.dp(this, 62), UiKit.dp(this, 62)));
        dateRow.addView(calendarHolder, new LinearLayout.LayoutParams(
                UiKit.dp(this, 68), UiKit.dp(this, 88)));

        LocalDate firstDate = targetDate.minusDays(1);
        for (int offset = 0; offset < 5; offset++) {
            LocalDate date = firstDate.plusDays(offset);
            dateRow.addView(buildDateChip(date, targetDate, policyDate));
            if (offset < 4) dateRow.addView(horizontalGap(6));
        }
        dateScroll.addView(dateRow);
        header.addView(dateScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 88)));
        screen.addView(header, UiKit.matchWrap());

        View sectionDivider = new View(this);
        sectionDivider.setBackgroundColor(UiKit.SOFT_GRAY);
        screen.addView(sectionDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 10)));

        ScrollView scheduleScroll = new ScrollView(this);
        scheduleScroll.setFillViewport(true);
        scheduleScroll.setClipToPadding(false);
        LinearLayout lessonList = UiKit.vertical(this);
        lessonList.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16),
                UiKit.dp(this, 16), UiKit.dp(this, 32));

        boolean studyDay = HolidayCalendar.isStudyDay(targetDate);
        List<LessonBlock> lessons = studyDay
                ? repository.getLessonsForDay(targetDate.getDayOfWeek().getValue())
                : new ArrayList<>();
        if (!studyDay) {
            String holiday = HolidayCalendar.holidayName(targetDate);
            lessonList.addView(emptyState(holiday.isEmpty() ? "Выходной" : holiday,
                    "В этот день уроков нет."));
        } else if (lessons.isEmpty()) {
            lessonList.addView(emptyState("Уроков нет", "На выбранный день расписание пока не заполнено."));
        } else {
            LessonTimeline.State timeline = LessonTimeline.evaluate(
                    targetDate, now.toLocalDate(), now.toLocalTime(), lessons);
            LessonBlock focusLesson = !scheduleDateManuallySelected
                    && targetDate.equals(now.toLocalDate()) ? timeline.focusLesson() : null;
            View autoScrollTarget = null;
            liveScheduleDate = targetDate;
            liveTimelineKey = timeline.key();
            lessonList.addView(buildLiveStatusCard(timeline));
            lessonList.addView(UiKit.space(this, 16));
            LessonBlock previous = null;
            for (int index = 0; index < lessons.size(); index++) {
                LessonBlock lesson = lessons.get(index);
                if (previous != null) {
                    lessonList.addView(buildBreakRow(targetDate, previous, lesson, timeline));
                    lessonList.addView(UiKit.space(this, 8));
                }
                boolean finished = LessonTimeline.isFinished(
                        targetDate, now.toLocalDate(), now.toLocalTime(), lesson);
                View lessonCard = buildLessonCard(lesson, targetDate, timeline, finished);
                lessonList.addView(lessonCard);
                if (focusLesson != null && index > 0 && focusLesson.id.equals(lesson.id)) {
                    autoScrollTarget = lessonCard;
                }
                lessonList.addView(UiKit.space(this, 8));
                previous = lesson;
            }
            if (autoScrollTarget != null) {
                scrollLessonToTop(scheduleScroll, lessonList, autoScrollTarget);
            }
        }
        scheduleScroll.addView(lessonList);
        screen.addView(scheduleScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return screen;
    }

    private void scrollLessonToTop(ScrollView scroll, LinearLayout list, View lessonCard) {
        scroll.post(() -> {
            if (!lessonCard.isAttachedToWindow()) return;
            int topGap = UiKit.dp(this, 16);
            int desiredScroll = Math.max(0, lessonCard.getTop() - topGap);
            int availableScroll = Math.max(0, list.getHeight() - scroll.getHeight());
            if (availableScroll < desiredScroll) {
                int extraBottomSpace = desiredScroll - availableScroll;
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(),
                        list.getPaddingRight(), list.getPaddingBottom() + extraBottomSpace);
            }
            scroll.post(() -> {
                if (lessonCard.isAttachedToWindow()) {
                    scroll.scrollTo(0, Math.max(0, lessonCard.getTop() - topGap));
                }
            });
        });
    }

    private View buildLiveStatusCard(LessonTimeline.State timeline) {
        LinearLayout card = UiKit.vertical(this);
        card.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 14),
                UiKit.dp(this, 18), UiKit.dp(this, 14));
        card.setBackground(UiKit.ripple(this, UiKit.SURFACE, 20, 1, UiKit.DUSTY_BLUE));
        liveScheduleTitle = UiKit.text(this, "", 16, UiKit.INK, true);
        liveScheduleSubtitle = UiKit.text(this, "", 13, UiKit.GRAY, false);
        card.addView(liveScheduleTitle, UiKit.matchWrap());
        card.addView(UiKit.space(this, 6));
        card.addView(liveScheduleSubtitle, UiKit.matchWrap());
        updateLiveStatus(timeline);
        return card;
    }

    private void updateLiveStatus(LessonTimeline.State timeline) {
        if (liveScheduleTitle == null || liveScheduleSubtitle == null) return;
        String title;
        String subtitle;
        switch (timeline.phase) {
            case CURRENT_LESSON:
                title = "Сейчас · " + timeline.currentLesson.subject;
                subtitle = "До звонка " + timeline.minutes + " мин";
                break;
            case BREAK:
                title = "Сейчас перемена";
                subtitle = timeline.nextLesson == null
                        ? "Следующий урок скоро"
                        : "До урока «" + timeline.nextLesson.subject + "» " + timeline.minutes + " мин";
                break;
            case NEXT_LESSON:
                title = "Следующий урок · " + timeline.nextLesson.subject;
                subtitle = "Начнётся через " + timeline.minutes + " мин";
                break;
            case FINISHED:
                title = "Уроки на сегодня закончились";
                subtitle = "Можно перейти к следующему учебному дню";
                break;
            case UPCOMING_DAY:
                title = "Ближайший урок · " + timeline.nextLesson.subject;
                subtitle = capitalize(liveScheduleDate.format(DAY_NAME)) + ", "
                        + liveScheduleDate.format(FULL_DATE) + " · "
                        + BellSchedule.blockTimeLabel(timeline.nextLesson.dayOfWeek,
                        timeline.nextLesson.startPeriod, timeline.nextLesson.endPeriod);
                break;
            default:
                title = "Уроков нет";
                subtitle = "Расписание на этот день не заполнено";
                break;
        }
        liveScheduleTitle.setText(title);
        liveScheduleSubtitle.setText(subtitle);
    }

    private View buildLessonCard(LessonBlock lesson, LocalDate lessonDate,
                                 LessonTimeline.State timeline, boolean finished) {
        LinearLayout card = UiKit.card(this, finished ? UiKit.MID_GRAY : UiKit.SURFACE);
        if (finished) {
            card.setBackground(UiKit.ripple(this, UiKit.MID_GRAY, 22, 1, UiKit.LINE));
        }
        boolean currentLesson = timeline.isCurrent(lesson);
        boolean nextLesson = !currentLesson && timeline.isNext(lesson);
        if (currentLesson || nextLesson) {
            card.setBackground(UiKit.ripple(this, UiKit.SURFACE, 22,
                    currentLesson ? 2 : 1, UiKit.DUSTY_BLUE));
        }
        card.setMinimumHeight(UiKit.dp(this, 92));
        card.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 18),
                UiKit.dp(this, 20), UiKit.dp(this, 18));
        Homework linkedHomework = findHomeworkForLesson(lesson.subject, lessonDate);
        card.setOnClickListener(view -> {
            if (linkedHomework == null) {
                showHomeworkDialogForSubject(lesson.subject, lessonDate);
            } else {
                showHomeworkDialog(linkedHomework, lessonDate);
            }
        });

        LinearLayout heading = UiKit.horizontal(this);
        heading.addView(UiKit.text(this, lesson.subject, 18,
                        finished ? UiKit.GRAY : UiKit.INK, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (currentLesson || nextLesson) {
            TextView badge = UiKit.text(this, currentLesson ? "сейчас" : "следующий",
                    10.5f, currentLesson ? UiKit.ON_ACCENT : UiKit.DUSTY_BLUE, true);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(UiKit.dp(this, 9), UiKit.dp(this, 5),
                    UiKit.dp(this, 9), UiKit.dp(this, 5));
            badge.setBackground(currentLesson
                    ? UiKit.gradientRipple(this, UiKit.DUSTY_BLUE, UiKit.BLUE_END, 14)
                    : UiKit.ripple(this, UiKit.SURFACE, 14, 1, UiKit.DUSTY_BLUE));
            heading.addView(badge);
            heading.addView(horizontalGap(8));
        }
        if (linkedHomework != null) {
            ImageView homeworkIcon = new ImageView(this);
            homeworkIcon.setImageResource(R.drawable.ic_homework_house);
            homeworkIcon.setColorFilter(UiKit.GRAY);
            homeworkIcon.setContentDescription("Есть домашнее задание");
            heading.addView(homeworkIcon, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 18), UiKit.dp(this, 18)));
            heading.addView(horizontalGap(8));
        }
        TextView arrow = UiKit.text(this, "›", 25, UiKit.GRAY, false);
        arrow.setGravity(Gravity.CENTER);
        heading.addView(arrow, new LinearLayout.LayoutParams(
                UiKit.dp(this, 24), ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(heading, UiKit.matchWrap());
        card.addView(UiKit.space(this, 10));

        LinearLayout details = UiKit.horizontal(this);
        details.addView(UiKit.text(this,
                        BellSchedule.blockTimeLabel(lesson.dayOfWeek, lesson.startPeriod, lesson.endPeriod),
                        15.5f, UiKit.GRAY, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView roomView = UiKit.text(this, RoomFormatter.compact(lesson.room),
                15, UiKit.GRAY, false);
        roomView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        details.addView(roomView);
        card.addView(details, UiKit.matchWrap());
        return card;
    }

    private View buildBreakRow(LocalDate date, LessonBlock previous, LessonBlock next,
                               LessonTimeline.State timeline) {
        LinearLayout row = UiKit.horizontal(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 34));
        row.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 4),
                UiKit.dp(this, 6), UiKit.dp(this, 4));

        int minutes = BellSchedule.breakMinutes(date.getDayOfWeek().getValue(),
                previous.endPeriod, next.startPeriod);
        ImageView walk = new ImageView(this);
        walk.setImageResource(R.drawable.ic_break_walk);
        walk.setColorFilter(UiKit.GRAY);
        walk.setContentDescription("Перемена");
        row.addView(walk, new LinearLayout.LayoutParams(
                UiKit.dp(this, 19), UiKit.dp(this, 19)));
        row.addView(horizontalGap(8));
        boolean currentBreak = timeline.phase == LessonTimeline.Phase.BREAK
                && timeline.currentLesson != null && timeline.nextLesson != null
                && timeline.currentLesson.id.equals(previous.id)
                && timeline.nextLesson.id.equals(next.id);
        row.addView(UiKit.text(this, "Перемена " + minutes + " минут", 14,
                        currentBreak ? UiKit.INK : UiKit.GRAY, currentBreak),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (currentBreak) {
            TextView badge = UiKit.text(this, "сейчас", 10, UiKit.ON_ACCENT, true);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4),
                    UiKit.dp(this, 8), UiKit.dp(this, 4));
            badge.setBackground(UiKit.gradientRipple(this, UiKit.DUSTY_BLUE, UiKit.BLUE_END, 12));
            row.addView(badge);
            row.addView(horizontalGap(8));
        }
        TextView time = UiKit.text(this,
                BellSchedule.breakTimeLabel(date.getDayOfWeek().getValue(),
                        previous.endPeriod, next.startPeriod),
                14, UiKit.GRAY, false);
        time.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(time);
        return row;
    }

    private View buildDateChip(LocalDate date, LocalDate selectedDate, LocalDate policyDate) {
        boolean selected = date.equals(selectedDate);
        LinearLayout holder = UiKit.vertical(this);
        holder.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        holder.setContentDescription(capitalize(date.format(DAY_NAME)) + ", " + date.format(FULL_DATE));
        holder.setFocusable(true);
        holder.setOnClickListener(view -> {
            selectedScheduleDate = date;
            scheduleDateManuallySelected = true;
            showScreen(Screen.SCHEDULE);
        });

        LinearLayout circle = UiKit.vertical(this);
        circle.setGravity(Gravity.CENTER);
        circle.setClickable(false);
        circle.setElevation(UiKit.dp(this, selected ? 5 : 2));
        circle.setBackground(selected
                ? UiKit.gradientRipple(this, UiKit.DUSTY_BLUE, UiKit.BLUE_END, 31)
                : UiKit.ripple(this, UiKit.SURFACE, 31, 1, UiKit.LESSON_BORDER));
        TextView number = UiKit.text(this, String.valueOf(date.getDayOfMonth()),
                20, selected ? UiKit.ON_ACCENT : UiKit.INK, false);
        number.setGravity(Gravity.CENTER);
        circle.addView(number, UiKit.matchWrap());
        TextView weekday = UiKit.text(this,
                date.format(DateTimeFormatter.ofPattern("EE", RUSSIAN)).replace(".", ""),
                11.5f, selected ? UiKit.ON_ACCENT : UiKit.GRAY, false);
        weekday.setGravity(Gravity.CENTER);
        circle.addView(weekday, UiKit.matchWrap());
        holder.addView(circle, new LinearLayout.LayoutParams(
                UiKit.dp(this, 62), UiKit.dp(this, 62)));

        String hint = date.equals(LocalDate.now()) ? "сегодня"
                : selected && date.equals(policyDate) ? "след. день" : "";
        TextView caption = UiKit.text(this, hint, 9.5f, UiKit.GRAY, false);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 22));
        captionParams.topMargin = UiKit.dp(this, 3);
        holder.addView(caption, captionParams);
        return holder;
    }

    private View buildScheduleEditor() {
        Page page = createPage();
        page.body.addView(buildBackHeader("Редактирование расписания", "Нажмите на урок, чтобы изменить предмет или кабинет.",
                Screen.SCHEDULE));
        page.body.addView(UiKit.space(this, 18));

        HorizontalScrollView dayScroll = new HorizontalScrollView(this);
        dayScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout dayRow = UiKit.horizontal(this);
        String[] days = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
        for (int index = 0; index < days.length; index++) {
            int day = index + 1;
            TextView chip = UiKit.smallButton(this, days[index], editorDay == day);
            chip.setOnClickListener(view -> {
                editorDay = day;
                showScreen(Screen.EDIT_SCHEDULE);
            });
            dayRow.addView(chip);
            if (index < days.length - 1) dayRow.addView(horizontalGap(8));
        }
        dayScroll.addView(dayRow);
        page.body.addView(dayScroll, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 18));

        List<LessonBlock> lessons = new ArrayList<>();
        for (LessonBlock lesson : repository.getSchedule()) {
            if (lesson.dayOfWeek == editorDay) lessons.add(lesson);
        }
        for (LessonBlock lesson : lessons) {
            LinearLayout card = UiKit.card(this, UiKit.SURFACE);
            LinearLayout heading = UiKit.horizontal(this);
            heading.addView(UiKit.text(this, periodLabel(lesson), 13, UiKit.DUSTY_BLUE, true));
            TextView change = UiKit.text(this, "Изменить", 13, UiKit.DUSTY_BLUE, true);
            change.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            heading.addView(change, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(heading);
            card.addView(UiKit.space(this, 10));
            card.addView(UiKit.text(this, lesson.subject.isEmpty() ? "Без предмета" : lesson.subject,
                    17, UiKit.INK, true));
            card.addView(UiKit.space(this, 5));
            card.addView(UiKit.text(this, RoomFormatter.editor(lesson.room),
                    13, UiKit.GRAY, false));
            card.setOnClickListener(view -> showLessonDialog(lesson));
            page.body.addView(card, UiKit.matchWrap());
            page.body.addView(UiKit.space(this, 10));
        }
        return page.scroll;
    }

    private View buildHomeworkScreen() {
        LocalDate dueDate = SchedulePolicy.nextHomeworkDate(LocalDate.now());
        Page page = createPage();
        page.body.addView(pageHeading("Домашние задания", 28));
        page.body.addView(UiKit.space(this, 5));
        page.body.addView(UiKit.text(this,
                "На " + dueDate.format(DAY_NAME) + ", " + dueDate.format(FULL_DATE),
                15, UiKit.GRAY, false));
        page.body.addView(UiKit.space(this, 18));

        LinearLayout answersCard = UiKit.card(this, UiKit.SURFACE);
        LinearLayout answersRow = UiKit.horizontal(this);
        LinearLayout answerText = UiKit.vertical(this);
        answerText.addView(UiKit.text(this, "С полученными ответами", 17, UiKit.INK, true));
        answerText.addView(UiKit.space(this, 5));
        int answerCount = repository.getAnswers().size();
        answerText.addView(UiKit.text(this, answerCount == 0 ? "Ответов пока нет" : answersCountLabel(answerCount),
                13, UiKit.GRAY, false));
        answersRow.addView(answerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = UiKit.text(this, "›", 28, UiKit.DUSTY_BLUE, false);
        arrow.setGravity(Gravity.CENTER);
        answersRow.addView(arrow);
        answersCard.addView(answersRow);
        answersCard.setOnClickListener(view -> showScreen(Screen.ANSWERS));
        page.body.addView(answersCard, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 12));

        TextView add = UiKit.primaryButton(this, "+  Добавить домашнее задание");
        add.setOnClickListener(view -> showHomeworkDialog(null, dueDate));
        page.body.addView(add, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 20));

        List<Homework> items = repository.getHomeworksForDate(dueDate);
        if (items.isEmpty()) {
            page.body.addView(UiKit.text(this, "Задания ещё не записаны", 15, UiKit.GRAY, false));
            page.body.addView(UiKit.space(this, 12));
        }
        for (Homework item : items) {
            page.body.addView(buildHomeworkCard(item, false));
            page.body.addView(UiKit.space(this, 12));
        }

        Set<String> listedSubjects = new LinkedHashSet<>();
        for (LessonBlock lesson : repository.getLessonsForDay(dueDate.getDayOfWeek().getValue())) {
            if (!listedSubjects.add(SubjectMatcher.normalize(lesson.subject))) continue;
            boolean hasHomework = false;
            for (Homework item : items) {
                if (SubjectMatcher.sameSubject(item.subject, lesson.subject)) {
                    hasHomework = true;
                    break;
                }
            }
            if (!hasHomework) {
                page.body.addView(buildHomeworkPlaceholder(lesson.subject, dueDate));
                page.body.addView(UiKit.space(this, 12));
            }
        }
        return page.scroll;
    }

    private View buildHomeworkPlaceholder(String subject, LocalDate dueDate) {
        LinearLayout card = UiKit.card(this, UiKit.SURFACE);
        LinearLayout row = UiKit.horizontal(this);
        LinearLayout text = UiKit.vertical(this);
        text.addView(UiKit.text(this, subject, 17, UiKit.INK, true));
        text.addView(UiKit.space(this, 5));
        text.addView(UiKit.text(this, "Домашнее задание не добавлено", 13, UiKit.GRAY, false));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = UiKit.smallButton(this, "+ Добавить", false);
        add.setOnClickListener(view -> showHomeworkDialogForSubject(subject, dueDate));
        row.addView(add);
        card.addView(row);
        card.setOnClickListener(view -> showHomeworkDialogForSubject(subject, dueDate));
        return card;
    }

    private View buildAllHomeworkScreen() {
        Page page = createPage();
        LinearLayout titleRow = UiKit.horizontal(this);
        titleRow.addView(pageHeading("Все задания", 28),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = UiKit.smallButton(this, "+ Добавить", true);
        add.setOnClickListener(view -> showHomeworkDialog(null,
                SchedulePolicy.nextHomeworkDate(LocalDate.now())));
        titleRow.addView(add);
        page.body.addView(titleRow);
        page.body.addView(UiKit.space(this, 5));
        page.body.addView(UiKit.text(this, "По ближайшей дате урока", 15, UiKit.GRAY, false));
        page.body.addView(UiKit.space(this, 20));

        List<Homework> items = repository.getHomeworks();
        if (items.isEmpty()) {
            page.body.addView(emptyState("Список пуст", "Здесь появятся все известные домашние задания."));
            return page.scroll;
        }

        LocalDate groupDate = null;
        for (Homework item : items) {
            if (!item.dueDate.equals(groupDate)) {
                groupDate = item.dueDate;
                page.body.addView(UiKit.text(this, groupDateLabel(groupDate), 15, UiKit.INK, true));
                page.body.addView(UiKit.space(this, 9));
            }
            page.body.addView(buildHomeworkCard(item, true));
            page.body.addView(UiKit.space(this, 12));
        }
        return page.scroll;
    }

    private View buildHomeworkCard(Homework item, boolean showDate) {
        LinearLayout card = UiKit.card(this, statusBackground(item.status));
        card.setOnClickListener(view -> showHomeworkDialog(item, item.dueDate));
        LinearLayout heading = UiKit.horizontal(this);
        heading.addView(UiKit.text(this, item.subject, 18, UiKit.INK, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean done = item.status == HomeworkStatus.DONE;
        TextView completion = UiKit.text(this, "✓", 20,
                done ? UiKit.ON_ACCENT : UiKit.DUSTY_BLUE, true);
        completion.setGravity(Gravity.CENTER);
        completion.setContentDescription(done ? "Отметить невыполненным" : "Отметить выполненным");
        completion.setBackground(done
                ? UiKit.gradientRipple(this, UiKit.DUSTY_BLUE, UiKit.BLUE_END, 19)
                : UiKit.ripple(this, UiKit.SURFACE, 19, 1, UiKit.DUSTY_BLUE));
        completion.setOnClickListener(view -> {
            item.status = done ? HomeworkStatus.NOT_DONE : HomeworkStatus.DONE;
            repository.saveHomework(item);
            SchoolWidgetProvider.updateAll(this);
            showScreen(currentScreen);
        });
        heading.addView(completion, new LinearLayout.LayoutParams(
                UiKit.dp(this, 38), UiKit.dp(this, 38)));
        card.addView(heading);
        card.addView(UiKit.space(this, 10));
        card.addView(UiKit.text(this, item.description, 15.5f, UiKit.GRAY, false));
        if (showDate) {
            card.addView(UiKit.space(this, 8));
            card.addView(UiKit.text(this, item.dueDate.format(FULL_DATE), 12.5f, UiKit.GRAY, false));
        }
        card.addView(UiKit.space(this, 14));

        LinearLayout statuses = UiKit.horizontal(this);
        addStatusButton(statuses, item, HomeworkStatus.NOT_DONE, "Не сделано");
        statuses.addView(horizontalGap(6));
        addStatusButton(statuses, item, HomeworkStatus.PARTIAL, "Частично");
        statuses.addView(horizontalGap(6));
        addStatusButton(statuses, item, HomeworkStatus.DONE, "Готово");
        card.addView(statuses, UiKit.matchWrap());
        return card;
    }

    private void addStatusButton(LinearLayout row, Homework homework, HomeworkStatus status, String label) {
        boolean selected = homework.status == status;
        TextView button = UiKit.text(this, label, 11.5f,
                selected ? UiKit.ON_ACCENT : UiKit.GRAY, selected);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(UiKit.dp(this, 38));
        button.setPadding(UiKit.dp(this, 5), UiKit.dp(this, 8), UiKit.dp(this, 5), UiKit.dp(this, 8));
        button.setBackground(UiKit.ripple(this,
                selected ? statusChip(status) : UiKit.SURFACE,
                19, 1, selected ? Color.TRANSPARENT : UiKit.LINE));
        button.setOnClickListener(view -> {
            homework.status = status;
            repository.saveHomework(homework);
            SchoolWidgetProvider.updateAll(this);
            showScreen(currentScreen);
        });
        row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private View buildAnswersScreen() {
        Page page = createPage();
        page.body.addView(buildBackHeader("С полученными ответами",
                "Письма с темой «предмет.домашка.домашка»", Screen.HOMEWORK));
        page.body.addView(UiKit.space(this, 15));

        LinearLayout controls = UiKit.horizontal(this);
        TextView sync = UiKit.primaryButton(this, syncInProgress ? "Синхронизация…" : "Проверить почту");
        sync.setEnabled(!syncInProgress);
        sync.setAlpha(syncInProgress ? 0.65f : 1f);
        sync.setOnClickListener(view -> startEmailSync(true));
        controls.addView(sync, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(horizontalGap(8));
        TextView manual = UiKit.secondaryButton(this, "Добавить вручную");
        manual.setOnClickListener(view -> showManualAnswerDialog());
        controls.addView(manual, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.body.addView(controls, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 10));
        page.body.addView(UiKit.text(this, syncStatusText(), 12.5f,
                lastSyncError.isEmpty() ? UiKit.GRAY : UiKit.RED, false));
        page.body.addView(UiKit.space(this, 20));

        List<ReceivedAnswer> answers = repository.getAnswers();
        if (answers.isEmpty()) {
            page.body.addView(emptyState("Ответов пока нет",
                    "Настройте IMAP или добавьте ответ вручную. Пароль почты хранится в защищённом хранилище телефона."));
            return page.scroll;
        }

        Map<String, List<ReceivedAnswer>> grouped = new LinkedHashMap<>();
        for (ReceivedAnswer answer : answers) {
            if (!grouped.containsKey(answer.subject)) grouped.put(answer.subject, new ArrayList<>());
            grouped.get(answer.subject).add(answer);
        }
        for (Map.Entry<String, List<ReceivedAnswer>> group : grouped.entrySet()) {
            page.body.addView(UiKit.text(this, group.getKey(), 18, UiKit.INK, true));
            page.body.addView(UiKit.space(this, 9));
            for (ReceivedAnswer answer : group.getValue()) {
                LinearLayout card = UiKit.card(this, UiKit.SURFACE);
                card.addView(UiKit.text(this, "К уроку " + answer.lessonDate.format(FULL_DATE),
                        12.5f, UiKit.DUSTY_BLUE, true));
                card.addView(UiKit.space(this, 8));
                card.addView(UiKit.text(this, preview(answer.answerText, 150), 14.5f, UiKit.INK, false));
                card.addView(UiKit.space(this, 9));
                card.addView(UiKit.text(this, DATE_TIME.format(Instant.ofEpochMilli(answer.receivedAt)),
                        12, UiKit.GRAY, false));
                card.setOnClickListener(view -> showAnswerDetails(answer));
                page.body.addView(card, UiKit.matchWrap());
                page.body.addView(UiKit.space(this, 10));
            }
            page.body.addView(UiKit.space(this, 6));
        }
        return page.scroll;
    }

    private View buildSettingsScreen() {
        Page page = createPage();
        page.body.addView(pageHeading("Настройки", 28));
        page.body.addView(UiKit.space(this, 5));
        page.body.addView(UiKit.text(this, "Оформление, почта и расписание", 15, UiKit.GRAY, false));
        page.body.addView(UiKit.space(this, 22));

        page.body.addView(UiKit.text(this, "Оформление", 19, UiKit.INK, true));
        page.body.addView(UiKit.space(this, 7));
        page.body.addView(UiKit.text(this,
                "Тёмная тема применяется ко всем экранам и сохраняется после перезапуска.",
                13.5f, UiKit.GRAY, false));
        page.body.addView(UiKit.space(this, 10));
        CheckBox darkTheme = new CheckBox(this);
        darkTheme.setText("Тёмная тема");
        darkTheme.setTextColor(UiKit.INK);
        darkTheme.setTextSize(15);
        darkTheme.setChecked(UiKit.isDarkTheme());
        darkTheme.setButtonTintList(android.content.res.ColorStateList.valueOf(UiKit.DUSTY_BLUE));
        darkTheme.setOnClickListener(view -> {
            boolean enabled = darkTheme.isChecked();
            UiKit.saveDarkTheme(this, enabled);
            UiKit.applyTheme(enabled);
            SchoolWidgetProvider.updateAll(this);
            getIntent().putExtra(EXTRA_OPEN_SCREEN, "settings");
            recreate();
        });
        page.body.addView(darkTheme, UiKit.matchWrap());

        page.body.addView(UiKit.space(this, 22));
        page.body.addView(UiKit.divider(this));
        page.body.addView(UiKit.space(this, 22));

        page.body.addView(UiKit.text(this, "Почта для ответов", 19, UiKit.INK, true));
        page.body.addView(UiKit.space(this, 7));
        page.body.addView(UiKit.text(this,
                "Gmail проверяется при открытии раздела и автоматически в фоне примерно раз в час. Нужен отдельный пароль приложения Google, а не обычный пароль аккаунта.",
                13.5f, UiKit.GRAY, false));
        page.body.addView(UiKit.space(this, 14));

        EmailSettings saved = repository.getEmailSettings();
        CheckBox enabled = new CheckBox(this);
        enabled.setText("Включить синхронизацию");
        enabled.setTextColor(UiKit.INK);
        enabled.setTextSize(15);
        enabled.setChecked(saved.enabled);
        enabled.setButtonTintList(android.content.res.ColorStateList.valueOf(UiKit.DUSTY_BLUE));
        page.body.addView(enabled, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 10));

        EditText username = UiKit.input(this, "Адрес электронной почты", false);
        username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        username.setText(saved.username);
        page.body.addView(username, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 10));

        EditText host = UiKit.input(this, "IMAP-сервер, например imap.gmail.com", false);
        host.setText(saved.host);
        page.body.addView(host, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 10));

        EditText port = UiKit.input(this, "Порт", false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(saved.port));
        page.body.addView(port, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 10));

        EditText password = UiKit.input(this,
                passwordStore.load().isEmpty() ? "Пароль приложения" : "Пароль сохранён — оставьте поле пустым",
                false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.body.addView(password, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 14));

        TextView save = UiKit.primaryButton(this, "Сохранить настройки почты");
        save.setOnClickListener(view -> saveEmailForm(enabled, username, host, port, password, false));
        page.body.addView(save, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 9));
        TextView saveAndSync = UiKit.secondaryButton(this, "Сохранить и проверить подключение");
        saveAndSync.setOnClickListener(view -> saveEmailForm(enabled, username, host, port, password, true));
        page.body.addView(saveAndSync, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 12));
        page.body.addView(UiKit.text(this,
                "School уведомит о новом ответе сразу после фоновой проверки почты и напомнит о невыполненной домашке примерно в 19:00.",
                12.5f, UiKit.GRAY, false));

        page.body.addView(UiKit.space(this, 28));
        page.body.addView(UiKit.divider(this));
        page.body.addView(UiKit.space(this, 24));
        page.body.addView(UiKit.text(this, "Виджет", 19, UiKit.INK, true));
        page.body.addView(UiKit.space(this, 7));
        page.body.addView(UiKit.text(this,
                "Показывает текущий или ближайший урок, кабинет и количество невыполненных заданий прямо на главном экране.",
                13.5f, UiKit.GRAY, false));
        page.body.addView(UiKit.space(this, 13));
        TextView addWidget = UiKit.secondaryButton(this, "Добавить виджет на главный экран");
        addWidget.setOnClickListener(view -> requestWidgetPin());
        page.body.addView(addWidget, UiKit.matchWrap());

        page.body.addView(UiKit.space(this, 28));
        page.body.addView(UiKit.divider(this));
        page.body.addView(UiKit.space(this, 24));
        page.body.addView(UiKit.text(this, "Резервная копия", 19, UiKit.INK, true));
        page.body.addView(UiKit.space(this, 7));
        page.body.addView(UiKit.text(this,
                "Копия содержит расписание, домашние задания, ответы и адрес почты. Пароль Gmail в неё не входит. В системном окне сохранения можно выбрать Google Диск.",
                13.5f, UiKit.GRAY, false));
        page.body.addView(UiKit.space(this, 13));
        TextView createBackup = UiKit.primaryButton(this, "Создать резервную копию");
        createBackup.setOnClickListener(view -> createBackupDocument());
        page.body.addView(createBackup, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 9));
        TextView restoreBackup = UiKit.secondaryButton(this, "Восстановить из копии");
        restoreBackup.setOnClickListener(view -> openBackupDocument());
        page.body.addView(restoreBackup, UiKit.matchWrap());

        page.body.addView(UiKit.space(this, 28));
        page.body.addView(UiKit.divider(this));
        page.body.addView(UiKit.space(this, 24));
        page.body.addView(UiKit.text(this, "Каникулы", 19, UiKit.INK, true));
        page.body.addView(UiKit.space(this, 7));
        page.body.addView(UiKit.text(this,
                "Осенние: 1–8 ноября 2026\n"
                        + "Зимние: 25 декабря 2026 – 10 января 2027\n"
                        + "Весенние: 21–28 марта 2027\n"
                        + "Летние: 1 июня – 31 августа ежегодно",
                13.5f, UiKit.GRAY, false));

        page.body.addView(UiKit.space(this, 28));
        page.body.addView(UiKit.divider(this));
        page.body.addView(UiKit.space(this, 24));
        page.body.addView(UiKit.text(this, "Расписание", 19, UiKit.INK, true));
        page.body.addView(UiKit.space(this, 7));
        page.body.addView(UiKit.text(this,
                "Предметы и кабинеты можно вернуть к исходному расписанию. Перед сбросом при необходимости создайте резервную копию.",
                13.5f, UiKit.GRAY, false));
        page.body.addView(UiKit.space(this, 13));
        TextView reset = UiKit.secondaryButton(this, "Вернуть расписание из Excel");
        reset.setTextColor(UiKit.RED);
        reset.setOnClickListener(view -> confirmScheduleReset());
        page.body.addView(reset, UiKit.matchWrap());
        page.body.addView(UiKit.space(this, 24));
        page.body.addView(UiKit.text(this, "Версия " + APP_VERSION,
                12, UiKit.GRAY, false));
        return page.scroll;
    }

    private View buildBackHeader(String title, String subtitle, Screen destination) {
        LinearLayout wrapper = UiKit.vertical(this);
        LinearLayout row = UiKit.horizontal(this);
        TextView back = UiKit.text(this, "‹", 34, UiKit.INK, false);
        back.setGravity(Gravity.CENTER);
        back.setBackground(UiKit.ripple(this, UiKit.SURFACE, 22, 1, UiKit.LINE));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(view -> showScreen(destination));
        row.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));
        row.addView(horizontalGap(12));
        row.addView(pageHeading(title, 23),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wrapper.addView(row, UiKit.matchWrap());
        wrapper.addView(UiKit.space(this, 9));
        wrapper.addView(UiKit.text(this, subtitle, 13.5f, UiKit.GRAY, false));
        return wrapper;
    }

    private View emptyState(String title, String body) {
        LinearLayout card = UiKit.card(this, UiKit.SOFT_GRAY);
        card.setElevation(0);
        card.addView(UiKit.text(this, title, 17, UiKit.INK, true));
        card.addView(UiKit.space(this, 7));
        card.addView(UiKit.text(this, body, 13.5f, UiKit.GRAY, false));
        return card;
    }

    private void showLessonDialog(LessonBlock lesson) {
        LinearLayout form = dialogForm();
        EditText subject = UiKit.input(this, "Предмет", false);
        subject.setText(lesson.subject);
        form.addView(subject, UiKit.matchWrap());
        form.addView(UiKit.space(this, 10));
        EditText room = UiKit.input(this, "Кабинет", false);
        room.setText(lesson.room);
        form.addView(room, UiKit.matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(periodLabel(lesson))
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            roundDialog(dialog);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String subjectText = subject.getText().toString().trim();
                    if (subjectText.isEmpty()) {
                        subject.setError("Введите название предмета");
                        return;
                    }
                    repository.updateLesson(lesson.id, subjectText, room.getText().toString());
                    SchoolWidgetProvider.updateAll(this);
                    dialog.dismiss();
                    showScreen(currentScreen);
                });
        });
        dialog.show();
    }

    private void showHomeworkDialog(Homework existing, LocalDate defaultDate) {
        showHomeworkDialog(existing, defaultDate, null);
    }

    private void showHomeworkDialogForSubject(String subject, LocalDate defaultDate) {
        showHomeworkDialog(null, defaultDate, subject);
    }

    private void showHomeworkDialog(Homework existing, LocalDate defaultDate, String preselectedSubject) {
        LinearLayout form = dialogForm();
        List<String> subjects = repository.getKnownSubjects();
        if (existing != null && !subjects.contains(existing.subject)) subjects.add(0, existing.subject);
        Spinner subject = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, subjects);
        subject.setAdapter(adapter);
        subject.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6), UiKit.dp(this, 8), UiKit.dp(this, 6));
        subject.setBackground(UiKit.ripple(this, UiKit.SURFACE, 14, 1, UiKit.LINE));
        if (existing != null) subject.setSelection(Math.max(0, subjects.indexOf(existing.subject)));
        else if (preselectedSubject != null && subjects.contains(preselectedSubject)) {
            subject.setSelection(subjects.indexOf(preselectedSubject));
        }
        form.addView(subject, UiKit.matchWrap());
        form.addView(UiKit.space(this, 10));

        EditText description = UiKit.input(this, "Что задано", true);
        if (existing != null) description.setText(existing.description);
        form.addView(description, UiKit.matchWrap());
        form.addView(UiKit.space(this, 10));

        LocalDate[] selectedDate = {existing == null ? defaultDate : existing.dueDate};
        TextView dateButton = UiKit.secondaryButton(this,
                getString(R.string.date_button, selectedDate[0].format(FULL_DATE)));
        dateButton.setOnClickListener(view -> {
            LocalDate value = selectedDate[0];
            new DatePickerDialog(this, (picker, year, month, day) -> {
                selectedDate[0] = LocalDate.of(year, month + 1, day);
                dateButton.setText(getString(R.string.date_button, selectedDate[0].format(FULL_DATE)));
            }, value.getYear(), value.getMonthValue() - 1, value.getDayOfMonth()).show();
        });
        form.addView(dateButton, UiKit.matchWrap());

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Новое задание" : "Изменить задание")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null);
        if (existing != null) builder.setNeutralButton("Удалить", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            roundDialog(dialog);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
                String taskText = description.getText().toString().trim();
                if (taskText.isEmpty()) {
                    description.setError("Напишите, что задано");
                    return;
                }
                Homework homework = existing == null
                        ? new Homework(UUID.randomUUID().toString(), String.valueOf(subject.getSelectedItem()),
                        taskText, selectedDate[0], HomeworkStatus.NOT_DONE, System.currentTimeMillis())
                        : existing;
                homework.subject = String.valueOf(subject.getSelectedItem());
                homework.description = taskText;
                homework.dueDate = selectedDate[0];
                repository.saveHomework(homework);
                SchoolWidgetProvider.updateAll(this);
                dialog.dismiss();
                showScreen(currentScreen);
            });
            if (existing != null) {
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setTextColor(UiKit.RED);
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    AlertDialog confirmation = new AlertDialog.Builder(this)
                                .setTitle("Удалить задание?")
                                .setMessage(existing.subject + ": " + preview(existing.description, 100))
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить", (confirm, which) -> {
                                    repository.deleteHomework(existing.id);
                                    SchoolWidgetProvider.updateAll(this);
                                    dialog.dismiss();
                                    showScreen(currentScreen);
                                }).create();
                    confirmation.setOnShowListener(shown -> roundDialog(confirmation));
                    confirmation.show();
                });
            }
        });
        dialog.show();
    }

    private void showManualAnswerDialog() {
        LinearLayout form = dialogForm();
        List<String> subjects = repository.getKnownSubjects();
        Spinner subject = new Spinner(this);
        subject.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, subjects));
        subject.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6), UiKit.dp(this, 8), UiKit.dp(this, 6));
        subject.setBackground(UiKit.ripple(this, UiKit.SURFACE, 14, 1, UiKit.LINE));
        form.addView(subject, UiKit.matchWrap());
        form.addView(UiKit.space(this, 10));
        EditText answer = UiKit.input(this, "Текст готового ответа", true);
        answer.setMinLines(7);
        form.addView(answer, UiKit.matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Добавить ответ")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            roundDialog(dialog);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String body = answer.getText().toString().trim();
                    if (body.isEmpty()) {
                        answer.setError("Вставьте текст ответа");
                        return;
                    }
                    repository.addIncomingAnswer("manual:" + UUID.randomUUID(),
                            String.valueOf(subject.getSelectedItem()), body, LocalDate.now());
                    dialog.dismiss();
                    showScreen(Screen.ANSWERS);
                });
        });
        dialog.show();
    }

    private void showAnswerDetails(ReceivedAnswer answer) {
        LinearLayout body = dialogForm();
        body.addView(UiKit.text(this, "Что задано", 14, UiKit.DUSTY_BLUE, true));
        body.addView(UiKit.space(this, 7));
        body.addView(UiKit.text(this,
                answer.homeworkText.isEmpty() ? "Задание по этому предмету пока не добавлено." : answer.homeworkText,
                15, answer.homeworkText.isEmpty() ? UiKit.GRAY : UiKit.INK, false));
        body.addView(UiKit.space(this, 18));
        body.addView(UiKit.divider(this));
        body.addView(UiKit.space(this, 18));
        body.addView(UiKit.text(this, "Полученный ответ", 14, UiKit.DUSTY_BLUE, true));
        body.addView(UiKit.space(this, 7));
        body.addView(UiKit.text(this, answer.answerText, 15, UiKit.INK, false));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(answer.subject + " · " + answer.lessonDate.format(SHORT_DATE))
                .setView(scroll)
                .setPositiveButton("Закрыть", null)
                .create();
        dialog.setOnShowListener(ignored -> roundDialog(dialog));
        dialog.show();
    }

    private void saveEmailForm(CheckBox enabled, EditText username, EditText host,
                               EditText port, EditText password, boolean syncAfterSave) {
        String usernameText = username.getText().toString().trim();
        String hostText = host.getText().toString().trim();
        if (hostText.isEmpty()) hostText = inferImapHost(usernameText);
        int portNumber;
        try {
            portNumber = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException error) {
            port.setError("Укажите порт, обычно 993");
            return;
        }
        if (enabled.isChecked() && (usernameText.isEmpty() || hostText.isEmpty())) {
            if (usernameText.isEmpty()) username.setError("Введите адрес почты");
            if (hostText.isEmpty()) host.setError("Укажите IMAP-сервер вашего почтового сервиса");
            return;
        }

        try {
            String newPassword = password.getText().toString();
            if (!newPassword.isEmpty()) passwordStore.save(newPassword);
            if (enabled.isChecked() && passwordStore.load().isEmpty()) {
                password.setError("Введите пароль приложения");
                return;
            }
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось защитить пароль на устройстве", Toast.LENGTH_LONG).show();
            return;
        }

        repository.saveEmailSettings(new EmailSettings(enabled.isChecked(), hostText, portNumber, usernameText));
        BackgroundSyncScheduler.update(this);
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
        if (syncAfterSave) {
            showScreen(Screen.ANSWERS);
            startEmailSync(true);
        } else {
            showScreen(Screen.SETTINGS);
        }
    }

    private void maybeAutoSync() {
        if (syncInProgress) return;
        EmailSettings settings = repository.getEmailSettings();
        String password = passwordStore.load();
        long age = System.currentTimeMillis() - repository.getLastSyncAt();
        long sinceAttempt = System.currentTimeMillis() - lastSyncAttemptAt;
        if (settings.isReady(password) && age > BackgroundSyncScheduler.INTERVAL_MILLIS
                && sinceAttempt > BackgroundSyncScheduler.INTERVAL_MILLIS) startEmailSync(false);
    }

    private void startEmailSync(boolean userInitiated) {
        if (syncInProgress) return;
        EmailSettings settings = repository.getEmailSettings();
        String password = passwordStore.load();
        if (!settings.isReady(password)) {
            if (userInitiated) {
                Toast.makeText(this, "Сначала заполните настройки почты", Toast.LENGTH_LONG).show();
                showScreen(Screen.SETTINGS);
            }
            return;
        }
        if (!EmailSyncGate.tryStart()) {
            if (userInitiated) {
                Toast.makeText(this, "Почта уже проверяется в фоне", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        syncInProgress = true;
        lastSyncAttemptAt = System.currentTimeMillis();
        lastSyncError = "";
        if (currentScreen == Screen.ANSWERS) showScreen(Screen.ANSWERS);
        long lastUid = repository.getLastImapUid();
        backgroundExecutor.submit(() -> {
            try {
                ImapClient.SyncResult result = new ImapClient().sync(settings, password, lastUid);
                int added = 0;
                List<String> addedSubjects = new ArrayList<>();
                for (ImapClient.IncomingMessage message : result.messages) {
                    if (repository.addIncomingAnswer(message.messageKey, message.subject,
                            message.body, LocalDate.now())) {
                        added++;
                        addedSubjects.add(message.subject);
                    }
                }
                repository.setLastImapUid(result.highestUid);
                repository.setLastSyncAt(System.currentTimeMillis());
                NotificationHelper.showNewAnswers(this, addedSubjects);
                int addedCount = added;
                mainHandler.post(() -> {
                    syncInProgress = false;
                    lastSyncError = "";
                    Toast.makeText(this, addedCount == 0
                                    ? "Новых ответов нет"
                                    : "Добавлено ответов: " + addedCount,
                            Toast.LENGTH_LONG).show();
                    if (currentScreen == Screen.ANSWERS) showScreen(Screen.ANSWERS);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "Не удалось подключиться к почте" : error.getMessage();
                mainHandler.post(() -> {
                    syncInProgress = false;
                    lastSyncError = message;
                    if (currentScreen == Screen.ANSWERS) showScreen(Screen.ANSWERS);
                    if (userInitiated) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            } finally {
                EmailSyncGate.finish();
            }
        });
    }

    private void confirmScheduleReset() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Вернуть исходное расписание?")
                .setMessage("Изменённые названия предметов и кабинеты будут удалены. Домашние задания сохранятся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Вернуть", (confirmationDialog, which) -> {
                    repository.resetSchedule();
                    SchoolWidgetProvider.updateAll(this);
                    Toast.makeText(this, "Расписание восстановлено", Toast.LENGTH_SHORT).show();
                    showScreen(Screen.SCHEDULE);
                })
                .create();
        dialog.setOnShowListener(ignored -> roundDialog(dialog));
        dialog.show();
    }

    private void createBackupDocument() {
        String fileName = "School-backup-" + LocalDate.now() + ".json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, fileName);
        startActivityForResult(intent, REQUEST_CREATE_BACKUP);
    }

    private void requestWidgetPin() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, SchoolWidgetProvider.class);
        if (manager.isRequestPinAppWidgetSupported()
                && manager.requestPinAppWidget(provider, null, null)) {
            Toast.makeText(this, "Подтвердите добавление виджета на главном экране",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this,
                    "Зажмите свободное место на главном экране, выберите «Виджеты» и найдите School.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openBackupDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        startActivityForResult(intent, REQUEST_OPEN_BACKUP);
    }

    private void writeBackup(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Хранилище не открыло файл");
            output.write(repository.createBackupJson().getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "Резервная копия сохранена", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось сохранить копию: "
                    + safeErrorMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void readBackup(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Хранилище не открыло файл");
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder json = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) json.append(buffer, 0, count);
            confirmBackupRestore(json.toString());
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось прочитать копию: "
                    + safeErrorMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmBackupRestore(String json) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Восстановить резервную копию?")
                .setMessage("Текущее расписание, домашние задания и ответы будут заменены данными из копии. Пароль Gmail потребуется ввести заново.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Восстановить", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            roundDialog(dialog);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    repository.restoreBackup(json);
                    passwordStore.clear();
                    selectedScheduleDate = null;
                    scheduleDateManuallySelected = false;
                    BackgroundSyncScheduler.update(this);
                    HomeworkReminderScheduler.scheduleNext(this);
                    SchoolWidgetProvider.updateAll(this);
                    dialog.dismiss();
                    Toast.makeText(this,
                            "Копия восстановлена. Введите пароль Gmail заново.",
                            Toast.LENGTH_LONG).show();
                    showScreen(Screen.SETTINGS);
                } catch (Exception error) {
                    Toast.makeText(this, "Не удалось восстановить копию: "
                            + safeErrorMessage(error), Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
    }

    private LinearLayout dialogForm() {
        LinearLayout form = UiKit.vertical(this);
        form.setPadding(UiKit.dp(this, 22), UiKit.dp(this, 10), UiKit.dp(this, 22), 0);
        return form;
    }

    private void roundDialog(AlertDialog dialog) {
        if (dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(
                UiKit.shape(UiKit.SURFACE, UiKit.dp(this, 28), 0, Color.TRANSPARENT));
        dialog.getWindow().getDecorView().setClipToOutline(true);
        dialog.getWindow().getDecorView().setElevation(UiKit.dp(this, 14));
    }

    private Page createPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(UiKit.IVORY);
        LinearLayout body = UiKit.vertical(this);
        body.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 18),
                UiKit.dp(this, 18), UiKit.dp(this, 32));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Page(scroll, body);
    }

    private View horizontalGap(int widthDp) {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(this, widthDp), 1));
        return gap;
    }

    private TextView pageHeading(String value, float sizeSp) {
        return UiKit.text(this, value, sizeSp, UiKit.INK, true);
    }

    private Screen screenFromIntent(Intent intent) {
        if (intent == null) return Screen.SCHEDULE;
        String requested = intent.getStringExtra(EXTRA_OPEN_SCREEN);
        if ("homework".equals(requested)) return Screen.HOMEWORK;
        if ("answers".equals(requested)) return Screen.ANSWERS;
        return Screen.SCHEDULE;
    }

    private String safeErrorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "неизвестная ошибка" : message;
    }

    private String scheduleContextLabel(ZonedDateTime now, LocalDate targetDate) {
        if (targetDate.equals(now.toLocalDate())) return "Сегодня";
        return "След. учеб. день";
    }

    private String periodLabel(LessonBlock lesson) {
        if (lesson.startPeriod == lesson.endPeriod) return lesson.startPeriod + " урок";
        return lesson.startPeriod + "–" + lesson.endPeriod + " уроки";
    }

    private String groupDateLabel(LocalDate date) {
        String prefix;
        if (date.equals(LocalDate.now())) prefix = "Сегодня";
        else if (date.equals(LocalDate.now().plusDays(1))) prefix = "Завтра";
        else prefix = capitalize(date.format(DAY_NAME));
        return prefix + " · " + date.format(FULL_DATE);
    }

    private Homework findHomeworkForLesson(String subject, LocalDate lessonDate) {
        for (Homework homework : repository.getHomeworksForDate(lessonDate)) {
            if (SubjectMatcher.sameSubject(homework.subject, subject)) return homework;
        }
        return null;
    }

    private int lessonAccent(String subject) {
        String normalized = SubjectMatcher.normalize(subject);
        if (normalized.contains("физкультур")) return Color.rgb(181, 92, 96);
        if (normalized.contains("физика") || normalized.contains("хими")) {
            return Color.rgb(101, 31, 78);
        }
        if (normalized.contains("алгебр") || normalized.contains("геометри")) {
            return Color.rgb(79, 101, 165);
        }
        if (normalized.contains("английск") || normalized.contains("географ")
                || normalized.contains("биологи")) {
            return Color.rgb(104, 151, 113);
        }
        if (normalized.contains("обществ") || normalized.contains("истори")
                || normalized.equals("вов") || normalized.contains("искусств")) {
            return Color.rgb(211, 151, 67);
        }
        if (normalized.contains("информат")) return Color.rgb(73, 123, 132);
        if (normalized.contains("труд")) return Color.rgb(166, 111, 68);
        return Color.rgb(39, 93, 135);
    }

    private int subjectIcon(String subject) {
        String normalized = SubjectMatcher.normalize(subject);
        if (normalized.contains("английск") || normalized.contains("географ")) {
            return R.drawable.ic_subject_globe;
        }
        if (normalized.contains("обществ")) return R.drawable.ic_subject_people;
        if (normalized.contains("физкультур")) return R.drawable.ic_subject_sport;
        if (normalized.contains("физика")) return R.drawable.ic_subject_atom;
        if (normalized.contains("хими")) return R.drawable.ic_subject_flask;
        if (normalized.contains("алгебр") || normalized.contains("геометри")) {
            return R.drawable.ic_subject_calculator;
        }
        if (normalized.contains("истори") || normalized.equals("вов")) {
            return R.drawable.ic_subject_history;
        }
        if (normalized.contains("биологи")) return R.drawable.ic_subject_leaf;
        if (normalized.contains("информат")) return R.drawable.ic_subject_monitor;
        if (normalized.contains("искусств")) return R.drawable.ic_subject_palette;
        return R.drawable.ic_subject_book;
    }

    private int statusBackground(HomeworkStatus status) {
        return UiKit.SURFACE;
    }

    private int statusChip(HomeworkStatus status) {
        if (status == HomeworkStatus.DONE) return UiKit.GREEN;
        if (status == HomeworkStatus.PARTIAL) return UiKit.ORANGE;
        return UiKit.RED;
    }

    private int statusText(HomeworkStatus status) {
        if (status == HomeworkStatus.DONE) return UiKit.GREEN;
        if (status == HomeworkStatus.PARTIAL) return UiKit.ORANGE;
        return UiKit.RED;
    }

    private String syncStatusText() {
        if (syncInProgress) return "Подключаемся к почтовому серверу…";
        if (!lastSyncError.isEmpty()) return lastSyncError;
        long lastSync = repository.getLastSyncAt();
        if (lastSync == 0) return "Почта ещё не проверялась";
        return "Последняя проверка: " + DATE_TIME.format(Instant.ofEpochMilli(lastSync));
    }

    private String inferImapHost(String email) {
        String lower = email.toLowerCase(Locale.ROOT);
        if (lower.endsWith("@gmail.com")) return "imap.gmail.com";
        if (lower.endsWith("@yandex.ru") || lower.endsWith("@yandex.by") || lower.endsWith("@ya.ru")) {
            return "imap.yandex.ru";
        }
        if (lower.endsWith("@mail.ru") || lower.endsWith("@inbox.ru")
                || lower.endsWith("@bk.ru") || lower.endsWith("@list.ru")) return "imap.mail.ru";
        if (lower.endsWith("@outlook.com") || lower.endsWith("@hotmail.com")
                || lower.endsWith("@live.com")) return "outlook.office365.com";
        return "";
    }

    private String answersCountLabel(int count) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 14) return count + " ответов";
        if (mod10 == 1) return count + " ответ";
        if (mod10 >= 2 && mod10 <= 4) return count + " ответа";
        return count + " ответов";
    }

    private String preview(String value, int maxLength) {
        String singleLine = value.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= maxLength) return singleLine;
        return singleLine.substring(0, maxLength - 1).trim() + "…";
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(RUSSIAN) + value.substring(1);
    }

    private enum Screen {
        SCHEDULE,
        HOMEWORK,
        ALL_HOMEWORK,
        SETTINGS,
        ANSWERS,
        EDIT_SCHEDULE
    }

    private static final class NavigationItem {
        private final ImageView icon;
        private final TextView label;

        private NavigationItem(ImageView icon, TextView label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private static final class Page {
        private final ScrollView scroll;
        private final LinearLayout body;

        private Page(ScrollView scroll, LinearLayout body) {
            this.scroll = scroll;
            this.body = body;
        }
    }
}
