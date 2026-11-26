package app.morphe.extension.instagram.patches.story.viewers;

import java.util.Objects;

/** What Load all viewers does next, from Instagram's own paging state. */
final class ViewerPaging {
    enum Step {
        /** Ask Instagram for the next page. */
        REQUEST,
        /** Instagram's own request is still out. */
        WAIT,
        /** No more pages. */
        DONE,
        /** Instagram did not move on; give up for now. */
        STOP,
    }

    private ViewerPaging() {
    }

    static Step beforeRequest(boolean hasMore, boolean busy) {
        if (!hasMore) return Step.DONE;
        return busy ? Step.WAIT : Step.REQUEST;
    }

    /** After a rebuild of the list: a page counts when the cursor moved, whatever kind of viewers it held. */
    static Step afterRebuild(String cursorAtRequest, String cursor, boolean hasMore, boolean busy) {
        if (!Objects.equals(cursorAtRequest, cursor)) return hasMore ? Step.REQUEST : Step.DONE;
        if (!hasMore) return Step.DONE;
        return busy ? Step.WAIT : Step.STOP;
    }

    /** When a request had no answer in time: keep waiting while Instagram's request is out, up to {@code limit}. */
    static Step afterTimeout(boolean busy, long waited, long limit) {
        return busy && waited < limit ? Step.WAIT : Step.STOP;
    }
}
