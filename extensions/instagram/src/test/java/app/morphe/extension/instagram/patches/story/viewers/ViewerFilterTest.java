package app.morphe.extension.instagram.patches.story.viewers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ViewerFilterTest {
    private static final ViewerFilter.Viewer ANNA = new ViewerFilter.Viewer("1", "anna.k", "Anna Kowalska");
    private static final ViewerFilter.Viewer BEN = new ViewerFilter.Viewer("2", "benny", null);
    private static final ViewerFilter.Viewer CARLA = new ViewerFilter.Viewer("3", "c_rossi", "Carla Rossi");
    private static final ViewerFilter.Viewer UNKNOWN = new ViewerFilter.Viewer(null, null, null);
    private static final List<ViewerFilter.Viewer> VIEWERS = Arrays.asList(ANNA, BEN, CARLA, UNKNOWN);
    private static final Set<String> NONE = Collections.emptySet();

    private static List<String> ids(List<ViewerFilter.Viewer> viewers) {
        List<String> ids = new ArrayList<>();
        for (ViewerFilter.Viewer viewer : viewers) ids.add(viewer.id);
        return ids;
    }

    private static List<String> search(String query) {
        return ids(ViewerFilter.filter(VIEWERS, query, ViewerFilter.ALL, NONE, NONE, NONE));
    }

    @Test
    public void anEmptyQueryKeepsEveryViewerInOrder() {
        assertEquals(Arrays.asList("1", "2", "3", null), search(""));
        assertEquals(Arrays.asList("1", "2", "3", null), search("   "));
        assertEquals(Arrays.asList("1", "2", "3", null), search(null));
    }

    @Test
    public void theQueryMatchesUsernameOrNameInAnyCase() {
        assertEquals(Collections.singletonList("1"), search("ANNA"));
        assertEquals(Collections.singletonList("3"), search("rossi"));
        assertEquals(Collections.singletonList("2"), search(" @Ben "));
        assertEquals(Collections.emptyList(), search("zed"));
    }

    @Test
    public void filtersKeepOnlyViewersInTheirGroup() {
        Set<String> followers = new HashSet<>(Arrays.asList("1", "3"));
        Set<String> notBack = Collections.singleton("2");
        Set<String> starred = Collections.singleton("3");
        assertEquals(Arrays.asList("1", "3"), ids(ViewerFilter.filter(
                VIEWERS, "", ViewerFilter.FOLLOWERS, followers, notBack, starred)));
        assertEquals(Collections.singletonList("2"), ids(ViewerFilter.filter(
                VIEWERS, "", ViewerFilter.NOT_FOLLOWING_BACK, followers, notBack, starred)));
        assertEquals(Collections.singletonList("3"), ids(ViewerFilter.filter(
                VIEWERS, "", ViewerFilter.STARRED, followers, notBack, starred)));
        assertEquals(Collections.singletonList("1"), ids(ViewerFilter.filter(
                VIEWERS, "anna", ViewerFilter.FOLLOWERS, followers, notBack, starred)));
    }

    @Test
    public void withoutFollowDataTheFollowFiltersFindNobody() {
        assertEquals(Collections.emptyList(), ids(ViewerFilter.filter(
                VIEWERS, "", ViewerFilter.FOLLOWERS, NONE, NONE, NONE)));
    }
}
