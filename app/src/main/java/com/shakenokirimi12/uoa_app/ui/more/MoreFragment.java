package com.shakenokirimi12.uoa_app.ui.more;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.ui.util.MainTabs;

import java.util.List;

public class MoreFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_more, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 下タブに出していない画面は、すべてここに並べる (タブの表示設定、iOS と同じ)。
        // 一覧を手で持つと、タブから外した画面 (カレンダー・履修など) がどこからも開けなくなる。
        // タブの表示設定はこの画面から設定を経由して変えるので、戻ると onViewCreated から作り直される。
        List<String> moreKeys = MainTabs.moreTabKeys(
                com.shakenokirimi12.uoa_app.data.PreferenceManager.getInstance(requireContext()));
        addTabRows(view.findViewById(R.id.layout_more_features), moreKeys);

        view.findViewById(R.id.button_notifications).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_notifications));
        view.findViewById(R.id.button_settings).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_settings));
    }

    /** 機能のカードに、渡したキーの順で行を並べる。 */
    private void addTabRows(ViewGroup features, List<String> keys) {
        LayoutInflater inflater = getLayoutInflater();
        int index = 0;
        for (String key : keys) {
            if (index > 0) features.addView(inflater.inflate(R.layout.item_divider, features, false), index++);
            TextView row = (TextView) inflater.inflate(R.layout.item_more_row, features, false);
            row.setText(MainTabs.moreLabel(key));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(MainTabs.iconRes(key), 0, R.drawable.ic_chevron_right, 0);
            int destination = MainTabs.destinationId(key);
            row.setOnClickListener(v -> Navigation.findNavController(v).navigate(destination));
            features.addView(row, index++);
        }
    }
}
