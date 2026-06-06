package com.shakenokirimi12.uoa_app.ui.map;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.shakenokirimi12.uoa_app.R;

public class CampusMapFragment extends Fragment {

    private static final double CAMPUS_LAT = 37.5234;
    private static final double CAMPUS_LNG = 139.9388;
    private static final String CAMPUS_MAP_URL =
            "https://www.u-aizu.ac.jp/intro/outline/campus-map.html";

    private View frameOfficialMap;
    private View frameGpsMap;
    private WebView webviewMap;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_campus_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        frameOfficialMap = view.findViewById(R.id.frame_official_map);
        frameGpsMap = view.findViewById(R.id.frame_gps_map);
        webviewMap = view.findViewById(R.id.webview_map);

        WebSettings ws = webviewMap.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);

        MaterialButtonToggleGroup toggleMode = view.findViewById(R.id.toggle_map_mode);
        toggleMode.check(R.id.btn_mode_official);

        toggleMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_mode_official) {
                frameOfficialMap.setVisibility(View.VISIBLE);
                frameGpsMap.setVisibility(View.GONE);
            } else {
                frameOfficialMap.setVisibility(View.GONE);
                frameGpsMap.setVisibility(View.VISIBLE);
            }
        });

        loadOfficialMap();

        view.findViewById(R.id.button_open_google_maps).setOnClickListener(v -> {
            Uri gmmUri = Uri.parse("geo:" + CAMPUS_LAT + "," + CAMPUS_LNG
                    + "?q=" + CAMPUS_LAT + "," + CAMPUS_LNG + "(会津大学)");
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmUri);
            mapIntent.setPackage("com.google.android.apps.maps");
            if (mapIntent.resolveActivity(requireContext().getPackageManager()) != null) {
                startActivity(mapIntent);
            } else {
                Uri webUri = Uri.parse("https://www.google.com/maps/@"
                        + CAMPUS_LAT + "," + CAMPUS_LNG + ",17z");
                startActivity(new Intent(Intent.ACTION_VIEW, webUri));
            }
        });
    }

    private void loadOfficialMap() {
        String html = "<html><head>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0, user-scalable=yes'>"
                + "<style>body{margin:0;padding:0;background:#fafafa;display:flex;justify-content:center;align-items:center;min-height:100vh;}"
                + "img{max-width:100%;height:auto;}</style>"
                + "</head><body>"
                + "<img src='https://www.u-aizu.ac.jp/files/intro/outline/campusmap.png' "
                + "alt='Campus Map' />"
                + "</body></html>";
        webviewMap.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    @Override
    public void onDestroyView() {
        if (webviewMap != null) {
            webviewMap.destroy();
        }
        super.onDestroyView();
    }
}
