package com.shakenokirimi12.uoa_app.ui.timetable;

import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.timetable.SharedClass;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableDerivation;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableFriend;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableGrid;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareStore;
import com.shakenokirimi12.uoa_app.databinding.FragmentTimetableFriendBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 友達の時間割 (iOS の FriendTimetableDetailView)。自分と重ねて、同じ授業とどちらも空いているコマを見られる。 */
public class FriendTimetableFragment extends Fragment {
    public static final String ARG_FRIEND_ID = "friend_id";

    private FragmentTimetableFriendBinding binding;
    private String friendId;
    private List<SharedClass> mine = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTimetableFriendBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        friendId = getArguments() != null ? getArguments().getString(ARG_FRIEND_ID) : null;
        mine = TimetableDerivation.weeklyClasses(DataCache.getInstance(requireContext()).loadEvents(), System.currentTimeMillis());
        boolean canCompare = !mine.isEmpty();
        binding.buttonModeCompare.setEnabled(canCompare);
        binding.textCompareUnavailable.setVisibility(canCompare ? View.GONE : View.VISIBLE);
        binding.toggleMode.addOnButtonCheckedListener((group, id, checked) -> { if (checked) render(); });

        binding.buttonRename.setOnClickListener(v -> { TimetableFriend f = friend(); if (f != null) rename(f); });
        binding.buttonDelete.setOnClickListener(v -> { TimetableFriend f = friend(); if (f != null) delete(f); });
        render();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Nullable
    private TimetableFriend friend() {
        return friendId != null ? TimetableShareStore.getInstance(requireContext()).friend(friendId) : null;
    }

    private void render() {
        if (binding == null) return;
        TimetableFriend f = friend();
        if (f == null) {
            // 消した直後など。
            binding.textName.setText("この友達の時間割はありません");
            binding.textDetail.setText("");
            binding.toggleMode.setVisibility(View.GONE);
            binding.rowActions.setVisibility(View.GONE);
            binding.grid.removeAllViews();
            return;
        }
        boolean compare = binding.toggleMode.getCheckedButtonId() == R.id.button_mode_compare && !mine.isEmpty();
        binding.textName.setText(f.name);
        binding.textDetail.setText(TimetableDates.receivedLine(f.receivedAt, f.timetable.classes.size()));
        binding.grid.setData(f.timetable.classes, compare ? mine : null);
        binding.legend.getRoot().setVisibility(compare ? View.VISIBLE : View.GONE);
        binding.cardSummary.setVisibility(compare ? View.VISIBLE : View.GONE);
        binding.textCompareCounts.setVisibility(compare ? View.VISIBLE : View.GONE);
        if (compare) {
            binding.textCompareCounts.setText(countsLine(f.timetable.classes));
            renderSummary(f.timetable.classes);
        }
    }

    /** 「同じ授業 N コマ ・ どちらも空き M コマ」。表に出しているコマ (曜日 × 行) で数える。iOS と同じ。 */
    private String countsLine(List<SharedClass> friend) {
        int same = 0, free = 0;
        for (int day : TimetableGrid.days(friend, mine)) {
            for (int[] r : TimetableGrid.rows(friend, mine)) {
                TimetableGrid.Overlap o = TimetableGrid.overlap(friend, mine, day, r);
                if (o == TimetableGrid.Overlap.SAME) same++;
                else if (o == TimetableGrid.Overlap.BOTH_FREE) free++;
            }
        }
        return "同じ授業 " + same + " コマ ・ どちらも空き " + free + " コマ";
    }

    /** 「どちらも空き」と「同じ授業」を文で並べる。表で色を見分けにくい人にも分かるように。 */
    private void renderSummary(List<SharedClass> friend) {
        LinearLayout box = binding.summary;
        box.removeAllViews();
        List<Integer> days = TimetableGrid.days(friend, mine);
        List<int[]> rows = TimetableGrid.rows(friend, mine);

        box.addView(heading("どちらも空き"));
        boolean anyFree = false;
        for (int day : days) {
            List<int[]> slots = new ArrayList<>();
            for (int[] r : rows) {
                if (TimetableGrid.overlap(friend, mine, day, r) == TimetableGrid.Overlap.BOTH_FREE) slots.add(r);
            }
            if (slots.isEmpty()) continue;
            anyFree = true;
            box.addView(line(TimetableGrid.dayLabel(day), TimetableGrid.freeSummary(slots, rows)));
        }
        if (!anyFree) box.addView(body("どちらも空いているコマはありません"));

        View divider = new View(requireContext());
        divider.setBackgroundColor(MaterialColors.getColor(box, com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        box.addView(divider, lp);

        box.addView(heading("同じ授業"));
        boolean anySame = false;
        for (int day : days) {
            for (int[] r : rows) {
                if (TimetableGrid.overlap(friend, mine, day, r) != TimetableGrid.Overlap.SAME) continue;
                Set<String> mineTitles = new HashSet<>();
                for (SharedClass c : TimetableGrid.classes(mine, day, r)) mineTitles.add(TimetableGrid.comparableTitle(c.title));
                String title = "";
                for (SharedClass c : TimetableGrid.classes(friend, day, r)) {
                    if (mineTitles.contains(TimetableGrid.comparableTitle(c.title))) { title = c.title; break; }
                }
                anySame = true;
                box.addView(line(TimetableGrid.dayLabel(day) + " " + TimetableGrid.rowLabel(r), title));
            }
        }
        if (!anySame) box.addView(body("同じ時間に受けている授業はありません"));
    }

    private TextView heading(String s) {
        TextView t = new TextView(requireContext());
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        t.setTextColor(MaterialColors.getColor(binding.summary, com.google.android.material.R.attr.colorOnSurface));
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(requireContext());
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setTextColor(MaterialColors.getColor(binding.summary, com.google.android.material.R.attr.colorOnSurfaceVariant));
        return t;
    }

    private View line(String label, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(2));
        TextView l = new TextView(requireContext());
        l.setText(label);
        l.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        l.setTextColor(MaterialColors.getColor(binding.summary, com.google.android.material.R.attr.colorOnSurface));
        l.setTypeface(l.getTypeface(), android.graphics.Typeface.BOLD);
        l.setMinWidth(dp(label.length() > 1 ? 80 : 24));
        TextView v = new TextView(requireContext());
        v.setText(value);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        v.setTextColor(MaterialColors.getColor(binding.summary, com.google.android.material.R.attr.colorOnSurface));
        row.addView(l);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vp.setMarginStart(dp(8));
        row.addView(v, vp);
        return row;
    }

    private void rename(TimetableFriend f) {
        TextInputLayout layout = new TextInputLayout(requireContext());
        TextInputEditText edit = new TextInputEditText(layout.getContext());
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        edit.setText(f.name);
        edit.setSelectAllOnFocus(true);
        layout.addView(edit);
        FrameLayout container = new FrameLayout(requireContext());
        int pad = dp(20);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(layout);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.timetable_rename)
                .setView(container)
                .setPositiveButton(R.string.timetable_change, (d, w) -> {
                    TimetableShareStore.getInstance(requireContext())
                            .rename(f.id, edit.getText() != null ? edit.getText().toString() : "");
                    render();
                })
                .setNegativeButton(R.string.timetable_cancel, null)
                .show();
    }

    private void delete(TimetableFriend f) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(f.name + " さんの時間割を削除しますか？")
                .setPositiveButton(R.string.timetable_delete, (d, w) -> {
                    TimetableShareStore.getInstance(requireContext()).remove(f.id);
                    if (getView() != null) Navigation.findNavController(getView()).popBackStack();
                })
                .setNegativeButton(R.string.timetable_cancel, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
