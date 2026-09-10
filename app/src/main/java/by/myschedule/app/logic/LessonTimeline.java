package by.myschedule.app.logic;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import by.myschedule.app.model.LessonBlock;

public final class LessonTimeline {
    private LessonTimeline() {
    }

    public static State evaluate(LocalDate scheduleDate, LocalDate today, LocalTime now,
                                 List<LessonBlock> lessons) {
        if (lessons.isEmpty()) return new State(Phase.EMPTY, null, null, 0);
        if (!scheduleDate.equals(today)) {
            return new State(Phase.UPCOMING_DAY, null, lessons.get(0), 0);
        }

        for (int index = 0; index < lessons.size(); index++) {
            LessonBlock lesson = lessons.get(index);
            LocalTime lessonStart = BellSchedule.period(
                    lesson.dayOfWeek, lesson.startPeriod).start;
            LocalTime lessonEnd = BellSchedule.period(
                    lesson.dayOfWeek, lesson.endPeriod).end;
            LessonBlock next = index + 1 < lessons.size() ? lessons.get(index + 1) : null;

            if (now.isBefore(lessonStart)) {
                if (index > 0) {
                    LessonBlock previous = lessons.get(index - 1);
                    LocalTime previousEnd = BellSchedule.period(
                            previous.dayOfWeek, previous.endPeriod).end;
                    if (!now.isBefore(previousEnd)) {
                        return new State(Phase.BREAK, previous, lesson,
                                minutesUntil(now, lessonStart));
                    }
                }
                return new State(Phase.NEXT_LESSON, null, lesson,
                        minutesUntil(now, lessonStart));
            }
            if (now.isBefore(lessonEnd)) {
                return new State(Phase.CURRENT_LESSON, lesson, next,
                        minutesUntil(now, lessonEnd));
            }
        }
        return new State(Phase.FINISHED, lessons.get(lessons.size() - 1), null, 0);
    }

    public static boolean isFinished(LocalDate scheduleDate, LocalDate today, LocalTime now,
                                     LessonBlock lesson) {
        if (scheduleDate.isBefore(today)) return true;
        if (scheduleDate.isAfter(today)) return false;
        LocalTime lessonEnd = BellSchedule.period(
                lesson.dayOfWeek, lesson.endPeriod).end;
        return !now.isBefore(lessonEnd);
    }

    private static int minutesUntil(LocalTime from, LocalTime to) {
        long seconds = Math.max(0, Duration.between(from, to).getSeconds());
        return (int) ((seconds + 59) / 60);
    }

    public enum Phase {
        EMPTY,
        UPCOMING_DAY,
        NEXT_LESSON,
        CURRENT_LESSON,
        BREAK,
        FINISHED
    }

    public static final class State {
        public final Phase phase;
        public final LessonBlock currentLesson;
        public final LessonBlock nextLesson;
        public final int minutes;

        private State(Phase phase, LessonBlock currentLesson, LessonBlock nextLesson, int minutes) {
            this.phase = phase;
            this.currentLesson = currentLesson;
            this.nextLesson = nextLesson;
            this.minutes = minutes;
        }

        public String key() {
            String currentId = currentLesson == null ? "" : currentLesson.id;
            String nextId = nextLesson == null ? "" : nextLesson.id;
            return phase.name() + ":" + currentId + ":" + nextId;
        }

        public boolean isCurrent(LessonBlock lesson) {
            return currentLesson != null && currentLesson.id.equals(lesson.id)
                    && phase == Phase.CURRENT_LESSON;
        }

        public boolean isNext(LessonBlock lesson) {
            return nextLesson != null && nextLesson.id.equals(lesson.id);
        }

        public LessonBlock focusLesson() {
            if (phase == Phase.CURRENT_LESSON) return currentLesson;
            if (phase == Phase.BREAK || phase == Phase.NEXT_LESSON) return nextLesson;
            return null;
        }
    }
}
