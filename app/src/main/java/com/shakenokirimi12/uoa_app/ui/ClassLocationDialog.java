package com.shakenokirimi12.uoa_app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

public class ClassLocationDialog {

    private static final double CAMPUS_LAT = 37.5234;
    private static final double CAMPUS_LNG = 139.9388;

    public static void show(Context context, GroupedClass cls) {
        View dialogView = LayoutInflater.from(context)
                .inflate(R.layout.dialog_class_location, null);

        TextView textClassName = dialogView.findViewById(R.id.text_class_name);
        TextView textLocation = dialogView.findViewById(R.id.text_location);
        WebView webView = dialogView.findViewById(R.id.webview_location);

        textClassName.setText(cls.getSummary());
        textLocation.setText(cls.getLocation() != null && !cls.getLocation().isEmpty()
                ? cls.getLocation() : "教室情報なし");

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);

        String html = buildMapHtml(cls.getLocation());
        webView.loadDataWithBaseURL("https://www.openstreetmap.org",
                html, "text/html", "UTF-8", null);

        dialogView.findViewById(R.id.button_open_maps).setOnClickListener(v -> {
            String query = cls.getLocation() != null && !cls.getLocation().isEmpty()
                    ? cls.getLocation() + " 会津大学" : "会津大学";
            Uri gmmUri = Uri.parse("geo:" + CAMPUS_LAT + "," + CAMPUS_LNG
                    + "?q=" + Uri.encode(query));
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmUri);
            context.startActivity(mapIntent);
        });

        new MaterialAlertDialogBuilder(context)
                .setView(dialogView)
                .setPositiveButton("閉じる", null)
                .show();
    }

    private static String buildMapHtml(String location) {
        return "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<style>body{margin:0}#map{width:100%;height:100vh}</style>"
                + "</head><body>"
                + "<div id='map'></div>"
                + "<script>"
                + "var map=L.map('map').setView([" + CAMPUS_LAT + "," + CAMPUS_LNG + "],17);"
                + "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{"
                + "attribution:'© OSM'}).addTo(map);"
                + "L.marker([" + CAMPUS_LAT + "," + CAMPUS_LNG + "])"
                + ".addTo(map).bindPopup('" + escapeJs(location != null ? location : "会津大学") + "').openPopup();"
                + "</script></body></html>";
    }

    private static String escapeJs(String s) {
        return s.replace("'", "\\'").replace("\n", "\\n");
    }
}
