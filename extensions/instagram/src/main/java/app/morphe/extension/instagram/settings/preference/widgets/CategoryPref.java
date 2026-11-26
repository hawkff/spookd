/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class CategoryPref extends PreferenceCategory {
    private boolean firstCategory;

    public CategoryPref(Context context) {
        super(context);
    }

    public CategoryPref(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CategoryPref(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setFirstCategory(boolean firstCategory) {
        this.firstCategory = firstCategory;
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        header.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // The band between sections, as in Instagram's settings.
        View sectionBreak = new View(context);
        sectionBreak.setBackgroundColor(InstagramPreferenceStyle.separatorColor());
        header.addView(sectionBreak, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                InstagramPreferenceStyle.dp(context, SpookdType.SECTION_BREAK)
        ));

        TextView title = new TextView(context);
        SpookdType.apply(title, SpookdType.SECTION);
        title.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
        title.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        int edge = InstagramPreferenceStyle.dp(context, SpookdType.SPACE_L);
        title.setPadding(edge, edge, edge, InstagramPreferenceStyle.dp(context, SpookdType.SPACE_M));
        header.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        return header;
    }

    @Override
    protected void onBindView(View view) {
        ViewGroup header = (ViewGroup) view;
        header.getChildAt(0).setVisibility(firstCategory ? View.GONE : View.VISIBLE);
        TextView title = (TextView) header.getChildAt(1);
        title.setText(getTitle());
        title.setEnabled(isEnabled());
    }
}
