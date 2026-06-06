package com.shakenokirimi12.uoa_app.ui.more;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.shakenokirimi12.uoa_app.R;

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

        view.findViewById(R.id.button_reviews).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_reviews));
        view.findViewById(R.id.button_grades).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_grades));
        view.findViewById(R.id.button_gakushoku).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.navigation_gakushoku));
        view.findViewById(R.id.button_facilities).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_facilities));
        view.findViewById(R.id.button_campus_map).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_campus_map));
        view.findViewById(R.id.button_notifications).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_notifications));
        view.findViewById(R.id.button_settings).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_more_to_settings));
    }
}
